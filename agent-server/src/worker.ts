import { RpcTarget } from 'cloudflare:workers';
import { createDeviceAuth } from './device-auth.ts';
import { createCloudTasks } from './cloud-tasks.ts';
import { CloudError, type CloudTaskView } from './cloud-task-contracts.ts';
import { parseHttpRequest, errorResponse, taskResponse } from './agent-http.ts';
import { createRuntimeLifetime } from './runtime-lifetime.ts';
import { runMaintenance } from './maintenance.ts';
import { voiceInput } from './voice-input.ts';
import { createBaiduTokenCache } from './baidu-token-cache.ts';
import { upgradeFunAsr } from './fun-asr-worker.ts';
export { LiuyingAgentRuntime } from './agent-runtime.ts';

// 仅有界保存应用凭证令牌；隔离实例回收即丢弃，不共享请求或网络Promise。
const voiceTokenCache = createBaiduTokenCache();

export default {
  async scheduled(_controller, env, _ctx): Promise<void> {
    try {
      const result = await runMaintenance({ db: env.DB });
      console.log(JSON.stringify({ event: 'agent_maintenance', status: 'completed', ...result }));
    } catch {
      console.error(JSON.stringify({ event: 'agent_maintenance', status: 'failed' }));
      throw new Error('maintenance_unavailable');
    }
  },
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (new URL(request.url).pathname === '/v1/agent/voice/stream') return upgradeFunAsr(request, env);
    if (new URL(request.url).pathname === '/v1/agent/voice') return voiceInput(request, env, fetch, voiceTokenCache);
    try {
      const route = await parseHttpRequest(request);
      const authorization = request.headers.get('Authorization') ?? '';
      const principal = await createDeviceAuth({ db: env.DB }).authenticate(authorization);
      if (route.kind === 'submit') {
        const lifetime = createRuntimeLifetime();
        try {
          let accepted!: (response: Response) => void;
          const receipt = new Promise<Response>(resolve => { accepted = resolve; });
          class ReceiptTarget extends RpcTarget {
            async accepted(task: CloudTaskView): Promise<void> { accepted(taskResponse(task, true)); }
          }
          const complete = Promise.resolve(env.AGENT_RUNTIME.getByName(principal.ownerId)
            .execute(authorization, route.input, new ReceiptTarget()));
          // 持有完整 RPC，而不是只持有早回执的竞争结果。
          ctx.waitUntil(complete.then(() => {}, () => {}));
          return await lifetime.wait(Promise.race([receipt, complete.then(reply => {
            throw new CloudError(reply.ok ? 'unavailable' : reply.error);
          })]));
        } finally { lifetime.close(); }
      }
      const tasks = createCloudTasks({ db: env.DB });
      const task = route.kind === 'find' ? await tasks.findByRequest(authorization, route.id)
        : await tasks[route.kind](authorization, route.id);
      if (route.kind === 'cancel' || route.kind === 'withdraw') {
        const lifetime = createRuntimeLifetime();
        const notify = Promise.resolve().then(() => env.AGENT_RUNTIME.getByName(principal.ownerId)
          .interrupt(authorization, route.id, route.kind as 'cancel' | 'withdraw'));
        ctx.waitUntil(lifetime.wait(notify).catch(() => {}).finally(() => lifetime.close()));
      }
      return taskResponse(task);
    } catch (error) { return errorResponse(error); }
  },
} satisfies ExportedHandler<Env>;
