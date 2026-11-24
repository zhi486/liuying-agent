import { DurableObject } from 'cloudflare:workers';
import { createSummaryService } from '../../src/summary-service.ts';
import { createSummaryDoStore } from '../../src/summary-do-store.ts';
import { CloudError } from '../../src/cloud-task-contracts.ts';

// 仅由独立本地测试配置加载，不导出到生产Worker。
export class SummaryProbe extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.marker = crypto.randomUUID();
    const storage = new Proxy(ctx.storage, { get: (target, key) => {
      if (key === 'sync') return async () => {
        if (this.failSync) throw new Error('合成持久化确认失败');
        await target.sync();
      };
      const value = Reflect.get(target, key, target);
      return typeof value === 'function' ? value.bind(target) : value;
    } });
    const store = createSummaryDoStore(storage);
    this.handle = createSummaryService({ store,
      accounting: { begin: async () => true, record: async () => {
        if (this.waitForReceipt) {
          this.waitForReceipt = false;
          await new Promise(resolve => { this.finishReceipt = resolve; });
        }
      } },
      authenticate: async authorization => {
        if (authorization !== 'Bearer synthetic') throw new CloudError('unauthenticated');
        return { ownerId: 'synthetic', deviceId: 'device' };
      },
      model: { generate: async () => {
        const count = (await ctx.storage.get('calls') ?? 0) + 1;
        await ctx.storage.put('calls', count);
        if (this.modelResult) return this.modelResult;
        if (this.waitForResult) {
          return new Promise(resolve => { this.finishResult = resolve; });
        }
        // 合成派发后失联，不使用任何网络。
        throw new Error('合成失联');
      } },
    });
  }
  async fetch(request) {
    const result = await this.handle(request);
    result.headers.set('X-Test-Instance', this.marker);
    return result;
  }
}
export default { fetch() { return new Response(null, { status: 404 }); } };
