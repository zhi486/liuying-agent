import { DurableObject } from 'cloudflare:workers';
import { createDeviceAuth } from './device-auth.ts';
import { createCloudTasks } from './cloud-tasks.ts';
import { createAgentService } from './agent-service.ts';
import { CloudError, parseSubmission, type CloudErrorCode, type CloudTaskView } from './cloud-task-contracts.ts';
import { createInputTokenCounter } from './token-count/counter.ts';
import { readRuntimeConfig } from './runtime-config.ts';
import { createRuntimeLifetime } from './runtime-lifetime.ts';
import tokenizerBytes from '../.generated/tokenizer.bin';

export type RuntimeReply = { ok: true; task: CloudTaskView } | { ok: false; error: CloudErrorCode };
export interface Receipt { accepted(task: CloudTaskView): Promise<void> }
type Service = ReturnType<typeof createAgentService>;
type ActiveExecution = { id: string; service: Service };

export class LiuyingAgentRuntime extends DurableObject<Env> {
  #serviceFactory: typeof createAgentService;
  #counter: ReturnType<typeof createInputTokenCounter> | undefined;
  #preparing: Promise<void> | undefined;
  #active: ActiveExecution | undefined;

  constructor(ctx: DurableObjectState, env: Env, serviceFactory = createAgentService) {
    super(ctx, env);
    this.#serviceFactory = serviceFactory;
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS runtime_owner (
      singleton INTEGER PRIMARY KEY CHECK (singleton = 1), owner TEXT NOT NULL, version INTEGER NOT NULL)`);
  }

  async #authenticate(authorization: string): Promise<void> {
    const principal = await createDeviceAuth({ db: this.env.DB }).authenticate(authorization);
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('INSERT OR IGNORE INTO runtime_owner VALUES (1, ?, 1)', principal.ownerId);
      const row = this.ctx.storage.sql.exec<{ owner: string; version: number }>(
        'SELECT owner, version FROM runtime_owner WHERE singleton=1').one();
      if (row.owner !== principal.ownerId) throw new CloudError('unauthenticated');
      if (row.version !== 1) throw new CloudError('unavailable');
    });
  }

  async #count(raw: string): Promise<number> {
    // 只缓存固定字典工厂，不缓存任意用户正文或其计数结果。
    const pending = this.#counter ??= createInputTokenCounter(new Uint8Array(tokenizerBytes));
    let counter: Awaited<typeof pending>;
    try { counter = await pending; }
    catch (error) { if (this.#counter === pending) this.#counter = undefined; throw error; }
    return counter(raw);
  }

  async execute(authorization: string, submission: unknown, receipt: Receipt): Promise<RuntimeReply> {
    const lifetime = createRuntimeLifetime();
    let ownSlot: ActiveExecution | undefined;
    try {
      await lifetime.wait(this.#authenticate(authorization));
      const parsed = parseSubmission(submission);
      while (this.#preparing) await lifetime.wait(this.#preparing);
      let release!: () => void;
      const gate = new Promise<void>(resolve => { release = resolve; });
      this.#preparing = gate;
      let prepared: Awaited<ReturnType<Service['prepare']>>;
      try {
        const tasks = createCloudTasks({ db: this.env.DB });
        let exists = false;
        try {
          await lifetime.wait(tasks.findByRequest(authorization, parsed.clientRequestId));
          exists = true;
        } catch (error) {
          if (!(error instanceof CloudError) || error.code !== 'not_found') throw error;
          if (this.#active) throw new CloudError('busy');
        }
        if (exists) {
          // 重放不需要供应商配置，但必须复用正式指纹校验，不能仅凭请求键返回。
          const accepted = await lifetime.wait(tasks.accept(authorization, submission));
          if (accepted.created) throw new CloudError('unavailable');
          prepared = { task: accepted.task, run: null };
        } else {
          const config = readRuntimeConfig(this.env);
          const service = this.#serviceFactory({ db: this.env.DB, ...config, countInputTokens: raw => this.#count(raw) });
          prepared = await lifetime.wait(service.prepare(authorization, submission));
          if (prepared.run) {
            lifetime.setRemaining(prepared.task.deadlineAtMillis - Date.now());
            ownSlot = { id: prepared.task.id, service };
            this.#active = ownSlot;
          }
        }
      } finally {
        if (this.#preparing === gate) this.#preparing = undefined;
        release();
      }
      // 必须先启动已接单执行；回执失败不能取消执行，也不能创建第二次 run。
      const work = prepared.run?.({ waitUntil: pending => lifetime.waitUntil(pending) });
      // 回执通道属于本次 RPC，但不是执行/账务收尾，不能等它才能释放执行槽。
      void Promise.resolve().then(() => receipt.accepted(prepared.task)).catch(() => {});
      let failure: unknown;
      if (work) {
        try { await lifetime.wait(work); } catch (error) { failure = error; }
      }
      await lifetime.drain();
      if (failure) throw failure;
      const task = await lifetime.wait(createCloudTasks({ db: this.env.DB }).get(authorization, prepared.task.id));
      return { ok: true, task };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : 'unavailable' };
    } finally {
      lifetime.close();
      if (ownSlot && this.#active === ownSlot) this.#active = undefined;
    }
  }

  async interrupt(authorization: string, taskId: string, action: 'cancel' | 'withdraw'): Promise<void> {
    const lifetime = createRuntimeLifetime();
    try {
      await lifetime.wait(this.#authenticate(authorization));
      if (action !== 'cancel' && action !== 'withdraw') return;
      const active = this.#active;
      if (active?.id === taskId) await lifetime.wait(active.service[action](authorization, taskId));
    } catch { /* 通知是尽力而为；HTTP 控制已先在 D1 持久化。 */ }
    finally { lifetime.close(); }
  }
}
