import { DurableObject } from 'cloudflare:workers';
import { createDeviceAuth, digest, type Principal } from './device-auth.ts';
import { createCloudTasks } from './cloud-tasks.ts';
import { createAgentService } from './agent-service.ts';
import { CloudError, DATABASE_TIME_SQL, parseSubmission, uuid, type CloudErrorCode, type CloudTaskView } from './cloud-task-contracts.ts';
import { createInputTokenCounter } from './token-count/counter.ts';
import { readRuntimeConfig } from './runtime-config.ts';
import { createRuntimeLifetime } from './runtime-lifetime.ts';
import { NativeMemoryChannel, type NativeMemoryRequest } from './native-memory-channel.ts';
import { NativeMcpChannel, NativeMcpError, type NativeMcpRequest } from './native-mcp-channel.ts';
import { createMcpStore, McpStoreError } from './mcp-store.ts';
import { discoverMcpHttp } from './mcp-http.ts';
import { createNativeMcpTask, type McpTaskData } from './native-mcp-task.ts';
import { parseMcpTaskPayload, type McpTaskAction } from './mcp-task-contracts.ts';
import { MCP_DISCOVERY_TIMEOUT_MS, parseMcpDiscoveryInput, parseMcpDiscoveryView, type McpDiscoveryView,
  type McpDiscoveryReply, type McpDiscoveryReceipt } from './mcp-discovery.ts';
import tokenizerBytes from '../.generated/tokenizer.bin';
import { TaskEventStore } from './task-event-store.ts';
import { eventCursor, type TaskEventBatch } from './task-events.ts';

export type RuntimeReply = { ok: true; task: CloudTaskView } | { ok: false; error: CloudErrorCode };
export type PurgeReply = { ok: true; requestId: string; purged: true } | { ok: false; error: CloudErrorCode };
export interface Receipt { accepted(task: CloudTaskView): Promise<void> }
type Service = ReturnType<typeof createAgentService>;
type ActiveExecution = { id: string; service: Service; deviceId: string;
  memory?: NativeMemoryChannel; mcp?: Awaited<ReturnType<typeof createNativeMcpTask>>; mcpClosed?: boolean };
type DiscoverySlot = { view: McpDiscoveryView; principal: Principal; controller: AbortController; channel: NativeMcpChannel; transportComplete: boolean };

export class LiuyingAgentRuntime extends DurableObject<Env> {
  #serviceFactory: typeof createAgentService;
  #counter: ReturnType<typeof createInputTokenCounter> | undefined;
  #preparing: Promise<void> | undefined;
  #active: ActiveExecution | undefined;
  #discovery: DiscoverySlot | undefined;
  #events: TaskEventStore;

  constructor(ctx: DurableObjectState, env: Env, serviceFactory = createAgentService) {
    super(ctx, env);
    this.#serviceFactory = serviceFactory;
    this.#events = new TaskEventStore(ctx.storage);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS runtime_owner (
      singleton INTEGER PRIMARY KEY CHECK (singleton = 1), owner TEXT NOT NULL, version INTEGER NOT NULL)`);
    // 只保存有界操作元数据；先落标记再启动 SDK，不把丢失的内存通道恢复成新请求。
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS runtime_mcp_discovery (
      request_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
      created_at INTEGER NOT NULL, view_json TEXT NOT NULL)`);
  }

  async #authenticate(authorization: string) {
    const principal = await createDeviceAuth({ db: this.env.DB }).authenticate(authorization);
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('INSERT OR IGNORE INTO runtime_owner VALUES (1, ?, 1)', principal.ownerId);
      const row = this.ctx.storage.sql.exec<{ owner: string; version: number }>(
        'SELECT owner, version FROM runtime_owner WHERE singleton=1').one();
      if (row.owner !== principal.ownerId) throw new CloudError('unauthenticated');
      if (row.version !== 1) throw new CloudError('unavailable');
    });
    return principal;
  }

  async #count(raw: string): Promise<number> {
    // 只缓存固定字典工厂，不缓存任意用户正文或其计数结果。
    const pending = this.#counter ??= createInputTokenCounter(new Uint8Array(tokenizerBytes));
    let counter: Awaited<typeof pending>;
    try { counter = await pending; }
    catch (error) { if (this.#counter === pending) this.#counter = undefined; throw error; }
    return counter(raw);
  }

  async execute(authorization: string, submission: unknown, receipt: Receipt, streaming = false): Promise<RuntimeReply> {
    const lifetime = createRuntimeLifetime();
    let ownSlot: ActiveExecution | undefined;
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      if (typeof streaming !== 'boolean') throw new CloudError('invalid_request');
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
          if (this.#active || this.#discovery) throw new CloudError('busy');
        }
        if (exists) {
          // 重放不需要供应商配置，但必须复用正式指纹校验，不能仅凭请求键返回。
          const accepted = await lifetime.wait(tasks.accept(authorization, submission));
          if (accepted.created) throw new CloudError('unavailable');
          prepared = { task: accepted.task, run: null };
        } else {
          const config = readRuntimeConfig(this.env);
          const service = this.#serviceFactory({ db: this.env.DB, ...config, countInputTokens: raw => this.#count(raw),
            ...(streaming ? { events: (taskId: string) => this.#events.port(taskId, signal => {
              signal.throwIfAborted();
              if (!ownSlot || this.#active !== ownSlot || ownSlot.id !== taskId || prepared.task.deadlineAtMillis <= Date.now())
                throw new CloudError('inactive');
            }) } : {}),
            memory: binding => {
              if (!ownSlot || this.#active !== ownSlot || ownSlot.id !== binding.taskId || ownSlot.memory)
                throw new CloudError('inactive');
              return ownSlot.memory = new NativeMemoryChannel({ ...binding, deviceId: principal.deviceId });
            },
            mcp: async (binding, signal) => {
              if (!binding.configuration) return undefined;
              const slot = ownSlot;
              if (!slot || this.#active !== slot || slot.id !== binding.execution.taskId || slot.mcp || binding.ownerId !== principal.ownerId)
                throw new CloudError('inactive');
              if (slot.mcpClosed) return undefined;
              const window = await createNativeMcpTask({ db: this.env.DB, principal, ...binding,
                configuration: binding.configuration, signal, active: () => this.#active === slot && !slot.mcpClosed });
              if (this.#active !== slot || signal.aborted) { window.close(); throw new CloudError('inactive'); }
              if (slot.mcpClosed) { window.close(); return undefined; }
              slot.mcp = window;
              return window.port;
            },
          });
          prepared = await lifetime.wait(service.prepare(authorization, submission));
          if (prepared.run) {
            if (streaming) this.#events.start(prepared.task.id, principal.deviceId);
            lifetime.setRemaining(prepared.task.deadlineAtMillis - Date.now());
            ownSlot = { id: prepared.task.id, service, deviceId: principal.deviceId };
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
      if (streaming && ownSlot) this.#events.finish(task);
      return { ok: true, task };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : 'unavailable' };
    } finally {
      lifetime.close();
      ownSlot?.memory?.close();
      ownSlot?.mcp?.close();
      if (ownSlot && this.#active === ownSlot) this.#active = undefined;
    }
  }

  /** 原任务只读事件查询；执行重建或断线不能启动模型补做。 */
  async events(authorization: string, taskId: string, after: number): Promise<
    { ok: true; batch: TaskEventBatch } | { ok: false; error: CloudErrorCode }> {
    const lifetime = createRuntimeLifetime();
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      const id = uuid(taskId);
      if (typeof after !== 'number' || eventCursor(String(after)) !== after) throw new CloudError('invalid_request');
      const task = await lifetime.wait(createCloudTasks({ db: this.env.DB }).get(authorization, id));
      if (!['queued', 'running'].includes(task.status)) {
        // 终态来自D1；崩溃丢失的内存执行只按原截止收敛，不由查询重新认领。
        this.#events.read(task, principal.deviceId, after);
        this.#events.finish(task);
      }
      return { ok: true, batch: this.#events.read(task, principal.deviceId, after) };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : 'unavailable' };
    } finally { lifetime.close(); }
  }

  #discoveryRecord(principal: Principal, id: string): McpDiscoveryView {
    const rows = this.ctx.storage.sql.exec<{ device_id: string; view_json: string }>(
      'SELECT device_id, view_json FROM runtime_mcp_discovery WHERE request_id=?', id).toArray();
    const row = rows[0];
    if (!row) throw new CloudError('not_found');
    if (row.device_id !== principal.deviceId) throw new CloudError('unauthenticated');
    const view = parseMcpDiscoveryView(JSON.parse(row.view_json));
    if (view.requestId !== id) throw new CloudError('unavailable');
    return view;
  }

  #saveDiscovery(view: McpDiscoveryView): McpDiscoveryView {
    const checked = parseMcpDiscoveryView(view);
    this.ctx.storage.sql.exec('UPDATE runtime_mcp_discovery SET view_json=? WHERE request_id=?', JSON.stringify(checked), checked.requestId);
    return checked;
  }

  #observeDiscovery(principal: Principal, id: string): McpDiscoveryView {
    const view = this.#discoveryRecord(principal, id);
    if (view.status !== 'running') return view;
    const active = this.#discovery;
    const reason = active?.view.requestId !== id ? 'interrupted' : view.deadlineAtMillis <= Date.now() ? 'timed_out' : null;
    if (!reason) return view;
    if (active?.view.requestId === id) { active.controller.abort(); active.channel.close(); }
    return this.#saveDiscovery({ ...view, status: 'unconfirmed', reason });
  }

  async #checkDiscovery(slot: DiscoverySlot | undefined, signal: AbortSignal, observeOnly = false): Promise<void> {
    try {
      if (!slot || this.#discovery !== slot || slot.controller.signal.aborted || signal.aborted || slot.view.deadlineAtMillis <= Date.now())
        throw new CloudError('inactive');
      const { view, principal } = slot;
      // 每次交付／回执都在 D1 执行时重核原设备和服务版本，不信任页面打开时的目录。
      const exists = await this.env.DB.prepare(`SELECT s.id FROM agent_mcp_services s JOIN agent_devices d ON d.owner_id=s.owner_id
        WHERE s.id=? AND s.owner_id=? AND s.revision=? AND s.endpoint=? AND d.id=?
        AND d.revoked_at IS NULL AND d.created_at<=${DATABASE_TIME_SQL} AND d.expires_at>${DATABASE_TIME_SQL}`)
        .bind(view.serviceId, principal.ownerId, view.revision, view.endpoint, principal.deviceId).first<string>('id');
      // 只读查询等待 D1 时 SDK 可能已收尾；随后由调用者重读持久状态，不再授权任何出网。
      if (observeOnly && (slot.transportComplete || this.#discovery !== slot)) return;
      if (!exists || this.#discovery !== slot || slot.controller.signal.aborted || signal.aborted || view.deadlineAtMillis <= Date.now())
        throw new CloudError('inactive');
    } catch {
      if (slot && this.#discovery === slot) { slot.controller.abort(); slot.channel.close(); }
      throw new CloudError('inactive');
    }
  }

  /** 独立配置操作，不创建聊天任务／模型请求；完整 RPC 持有 SDK 生命周期。 */
  async discoverMcp(authorization: string, input: unknown, receipt: McpDiscoveryReceipt): Promise<McpDiscoveryReply> {
    const lifetime = createRuntimeLifetime();
    let ownSlot: DiscoverySlot | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const parsed = parseMcpDiscoveryInput(input);
      const principal = await lifetime.wait(this.#authenticate(authorization));
      const fingerprint = await lifetime.wait(digest(JSON.stringify({ ...parsed, deviceId: principal.deviceId })));
      while (this.#preparing) await lifetime.wait(this.#preparing);
      let release!: () => void;
      const gate = new Promise<void>(resolve => { release = resolve; });
      this.#preparing = gate;
      try {
        const prior = this.ctx.storage.sql.exec<{ fingerprint: string }>(
          'SELECT fingerprint FROM runtime_mcp_discovery WHERE request_id=?', parsed.requestId).toArray()[0];
        if (prior) {
          const view = this.#observeDiscovery(principal, parsed.requestId);
          if (prior.fingerprint !== fingerprint) throw new CloudError('conflict');
          void Promise.resolve().then(() => receipt.accepted(view)).catch(() => {});
          return { ok: true, discovery: view };
        }
        if (this.#active || this.#discovery) throw new CloudError('busy');
        const initialStore = createMcpStore({ db: this.env.DB, principal, discover: async () => { throw new CloudError('unavailable'); } });
        const service = await lifetime.wait(initialStore.get(parsed.serviceId));
        if (service.revision !== parsed.revision) throw new CloudError('conflict');
        const startedAtMillis = Date.now();
        const view: McpDiscoveryView = { ...parsed, version: 'mcp_discovery_v1', endpoint: service.endpoint,
          startedAtMillis, deadlineAtMillis: startedAtMillis + MCP_DISCOVERY_TIMEOUT_MS,
          status: 'running', resultRevision: null, reason: null };
        // 24 小时内保留去重标记并限制为 64 次配置发现；到上限只拒绝，不驱逐未过期标记。
        this.ctx.storage.sql.exec('DELETE FROM runtime_mcp_discovery WHERE created_at<?', startedAtMillis - 86400000);
        if (this.ctx.storage.sql.exec<{ n: number }>('SELECT COUNT(*) AS n FROM runtime_mcp_discovery').one().n >= 64)
          throw new CloudError('busy');
        this.ctx.storage.sql.exec('INSERT INTO runtime_mcp_discovery VALUES (?,?,?,?,?)', parsed.requestId, principal.deviceId,
          fingerprint, startedAtMillis, JSON.stringify(view));
        const channel = new NativeMcpChannel({ ...principal, ...parsed, taskId: parsed.requestId, endpoint: service.endpoint },
          signal => this.#checkDiscovery(ownSlot, signal), MCP_DISCOVERY_TIMEOUT_MS);
        ownSlot = { view, principal, channel, controller: new AbortController(), transportComplete: false };
        this.#discovery = ownSlot;
        lifetime.setRemaining(MCP_DISCOVERY_TIMEOUT_MS);
      } finally { if (this.#preparing === gate) this.#preparing = undefined; release(); }
      const slot = ownSlot;
      if (!slot) throw new CloudError('unavailable');
      timer = setTimeout(() => { slot.controller.abort(); slot.channel.close(); }, MCP_DISCOVERY_TIMEOUT_MS);
      void Promise.resolve().then(() => receipt.accepted(slot.view)).catch(() => {});
      const store = createMcpStore({ db: this.env.DB, principal, discover: async (endpoint, signal) => {
        const catalog = await discoverMcpHttp({ endpoint, signal, timeoutMs: MCP_DISCOVERY_TIMEOUT_MS,
          send: request => slot.channel.send(request) });
        // SDK 已结束，无后续出网。D1 条件提交可能换 revision，查询不能把自己的提交误判为撤权。
        slot.transportComplete = true;
        return catalog;
      } });
      try {
        const found = await lifetime.wait(store.discover(parsed.serviceId, parsed.revision, slot.controller.signal));
        const current = this.#observeDiscovery(principal, parsed.requestId);
        return { ok: true, discovery: current.status !== 'running' ? current : this.#saveDiscovery({ ...current,
          status: 'completed', resultRevision: found.revision }) };
      } catch (error) {
        const current = this.#observeDiscovery(principal, parsed.requestId);
        return { ok: true, discovery: current.status !== 'running' ? current : this.#saveDiscovery({ ...current,
          status: 'unconfirmed', reason: error instanceof McpStoreError && error.code === 'stale_review' ? 'service_changed' : 'unavailable' }) };
      }
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : error instanceof McpStoreError && error.code === 'unavailable'
        ? 'not_found' : 'unavailable' };
    } finally {
      if (timer !== undefined) clearTimeout(timer);
      ownSlot?.controller.abort(); ownSlot?.channel.close();
      if (ownSlot && this.#discovery === ownSlot) this.#discovery = undefined;
      lifetime.close();
    }
  }

  async mcpDiscovery(authorization: string, requestId: string, action: 'get' | 'take' | 'reply', reply?: unknown): Promise<
    { ok: true; discovery: McpDiscoveryView; exchange: NativeMcpRequest | null } | { ok: false; error: CloudErrorCode }> {
    const lifetime = createRuntimeLifetime();
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      if (!['get', 'take', 'reply'].includes(action)) throw new CloudError('invalid_request');
      if (action !== 'reply' && reply !== undefined) throw new CloudError('invalid_request');
      // UUID 同样在 RPC 内验证，不能仅依赖外层 HTTP。
      const id = uuid(requestId);
      const discovery = this.#observeDiscovery(principal, id);
      if (action === 'get') {
        if (discovery.status === 'running' && !this.#discovery?.transportComplete)
          await lifetime.wait(this.#checkDiscovery(this.#discovery, new AbortController().signal, true));
        return { ok: true, discovery: this.#observeDiscovery(principal, id), exchange: null };
      }
      const active = this.#discovery;
      if (!active || active.view.requestId !== id || discovery.status !== 'running') throw new CloudError('inactive');
      if (action === 'take' && active.transportComplete) return { ok: true, discovery, exchange: null };
      if (action === 'reply') await lifetime.wait(active.channel.reply(principal, reply));
      return { ok: true, discovery, exchange: action === 'take' ? await lifetime.wait(active.channel.take(principal)) : null };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : error instanceof NativeMcpError
        ? error.code === 'scope_denied' ? 'unauthenticated' : error.code === 'conflict' ? 'conflict'
          : error.code === 'invalid_request' ? 'invalid_request' : 'inactive' : 'unavailable' };
    } finally { lifetime.close(); }
  }

  /** 任务插件路由只能操作原手机的当前窗口，不能重新打开或恢复已结束的 SDK 交付。 */
  async mcpTask(authorization: string, taskId: string, action: McpTaskAction, input?: unknown): Promise<
    { ok: true; data: McpTaskData } | { ok: false; error: CloudErrorCode }> {
    const lifetime = createRuntimeLifetime();
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      const id = uuid(taskId);
      if (!['get', 'take', 'reply', 'decision', 'close'].includes(action)) throw new CloudError('invalid_request');
      const payload = parseMcpTaskPayload(action, input);
      const active = this.#active;
      if (!active || active.id !== id) throw new CloudError('inactive');
      if (active.deviceId !== principal.deviceId) throw new CloudError('unauthenticated');
      if (action === 'close') {
        active.mcpClosed = true; active.mcp?.close();
        return { ok: true, data: { accepted: true } };
      }
      if (active.mcpClosed) throw new CloudError('inactive');
      const window = active.mcp;
      if (!window) {
        if (action === 'get' || action === 'take') return { ok: true, data: action === 'get' ? { confirmation: null } : { delivery: null } };
        throw new CloudError('inactive');
      }
      const data: McpTaskData = action === 'get' ? { confirmation: await lifetime.wait(window.get(principal)) }
        : action === 'take' ? { delivery: await lifetime.wait(window.take(principal)) }
        : action === 'decision' ? { decision: await lifetime.wait(window.decide(principal, payload)) }
        : (await lifetime.wait(window.reply(principal, payload)), { accepted: true });
      return { ok: true, data };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : error instanceof NativeMcpError
        ? error.code === 'scope_denied' ? 'unauthenticated' : error.code === 'conflict' ? 'conflict'
          : error.code === 'invalid_request' ? 'invalid_request' : 'inactive' : 'unavailable' };
    } finally { lifetime.close(); }
  }

  /** HTTP 和 RPC 均重新鉴权；同 owner 的其他设备也不能接管记忆工具。 */
  async memory(authorization: string, taskId: string, reply?: unknown): Promise<
    { ok: true; memory: NativeMemoryRequest | null } | { ok: false; error: CloudErrorCode }> {
    const lifetime = createRuntimeLifetime();
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      const active = this.#active;
      if (!active || active.id !== taskId) throw new CloudError('inactive');
      const task = await lifetime.wait(createCloudTasks({ db: this.env.DB }).get(authorization, taskId));
      if (this.#active !== active || !['queued', 'running'].includes(task.status) || task.deadlineAtMillis <= Date.now())
        throw new CloudError('inactive');
      const channel = active.memory;
      // 模型尚未请求工具时不暴露任何正文，仍限制为发起任务的手机。
      const device = await lifetime.wait(this.env.DB.prepare(`SELECT t.device_id FROM agent_cloud_tasks t
        JOIN agent_devices d ON d.id=t.device_id AND d.owner_id=t.owner_id
        WHERE t.id=? AND t.owner_id=? AND t.status IN ('queued','running') AND t.deadline_at>${DATABASE_TIME_SQL}
          AND d.revoked_at IS NULL AND d.created_at<=${DATABASE_TIME_SQL} AND d.expires_at>${DATABASE_TIME_SQL}`)
        .bind(taskId, principal.ownerId).first<string>('device_id'));
      if (device === null) throw new CloudError('inactive');
      if (device !== principal.deviceId || (channel && channel.deviceId !== principal.deviceId))
        throw new CloudError('unauthenticated');
      if (this.#active !== active || task.deadlineAtMillis <= Date.now()) throw new CloudError('inactive');
      if (reply !== undefined) {
        if (!channel) throw new CloudError('inactive');
        channel.reply(reply);
        return { ok: true, memory: null };
      }
      return { ok: true, memory: channel?.poll() ?? null };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : 'unavailable' };
    } finally { lifetime.close(); }
  }

  async interrupt(authorization: string, taskId: string, action: 'cancel' | 'withdraw' | 'acknowledge'): Promise<void> {
    const lifetime = createRuntimeLifetime();
    try {
      await lifetime.wait(this.#authenticate(authorization));
      if (action === 'withdraw' || action === 'acknowledge') this.#events.clear(uuid(taskId));
      if (action !== 'cancel' && action !== 'withdraw') return;
      const active = this.#active;
      if (active?.id === taskId) await lifetime.wait(active.service[action](authorization, taskId));
    } catch { /* 通知是尽力而为；HTTP 控制已先在 D1 持久化。 */ }
    finally { lifetime.close(); }
  }

  /** 删除回执必须等待原设备验证、活动通道关闭和事件持久清除，不能吞掉 DO 错误。 */
  async purgeRequest(authorization: string, requestId: string): Promise<PurgeReply> {
    const lifetime = createRuntimeLifetime();
    try {
      const principal = await lifetime.wait(this.#authenticate(authorization));
      const purged = await lifetime.wait(createCloudTasks({ db: this.env.DB }).purgeRequest(authorization, requestId));
      // 接单已在 D1 之前完成时，等待它安装执行槽，防止清理后又挂上内存通道／新事件流。
      while (this.#preparing) await lifetime.wait(this.#preparing);
      if (purged.taskId !== null) {
        const active = this.#active;
        if (active?.id === purged.taskId) {
          if (active.deviceId !== principal.deviceId) throw new CloudError('unauthenticated');
          this.#active = undefined;
          active.service.stop(active.id);
          active.memory?.close();
          active.mcpClosed = true;
          active.mcp?.close();
        }
        this.#events.clear(purged.taskId);
      }
      await lifetime.wait(this.ctx.storage.sync());
      return { ok: true, requestId: purged.requestId, purged: true };
    } catch (error) {
      return { ok: false, error: error instanceof CloudError ? error.code : 'unavailable' };
    } finally { lifetime.close(); }
  }
}
