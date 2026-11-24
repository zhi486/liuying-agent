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
import { mcpManagement } from './mcp-management.ts';
import { mcpDiscoveryRoute, readMcpDiscoveryBody, type McpDiscoveryView } from './mcp-discovery.ts';
import { taskEventsResponse } from './task-events.ts';
import type { PurgeReply } from './agent-runtime.ts';
export { LiuyingAgentRuntime } from './agent-runtime.ts';

// 仅有界保存应用凭证令牌；隔离实例回收即丢弃，不共享请求或网络Promise。
const voiceTokenCache = createBaiduTokenCache();

async function phoneDiscovery(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  try {
    const route = mcpDiscoveryRoute(request);
    const authorization = request.headers.get('Authorization') ?? '';
    const principal = await createDeviceAuth({ db: env.DB }).authenticate(authorization);
    const input = await readMcpDiscoveryBody(request, route);
    const runtime = env.AGENT_RUNTIME.getByName(principal.ownerId);
    const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { 'Cache-Control': 'no-store' } });
    if (route.kind === 'start') {
      const lifetime = createRuntimeLifetime();
      try {
        let accepted!: (response: Response) => void;
        const receipt = new Promise<Response>(resolve => { accepted = resolve; });
        class DiscoveryReceiptTarget extends RpcTarget {
          async accepted(discovery: McpDiscoveryView): Promise<void> {
            accepted(json({ discovery }, discovery.status === 'running' ? 202 : 200));
          }
        }
        const complete = Promise.resolve(runtime.discoverMcp(authorization, input, new DiscoveryReceiptTarget()));
        ctx.waitUntil(complete.then(() => {}, () => {}));
        return await lifetime.wait(Promise.race([receipt, complete.then(reply => {
          if (!reply.ok) throw new CloudError(reply.error);
          return json({ discovery: reply.discovery }, reply.discovery.status === 'running' ? 202 : 200);
        })]));
      } finally { lifetime.close(); }
    }
    const reply = await runtime.mcpDiscovery(authorization, route.id, route.kind, input);
    if (!reply.ok) throw new CloudError(reply.error);
    return json(route.kind === 'reply' ? { accepted: true } : route.kind === 'take' ? { exchange: reply.exchange } : { discovery: reply.discovery });
  } catch (error) {
    void request.body?.cancel().catch(() => {});
    return errorResponse(error);
  }
}

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
    const pathname = new URL(request.url).pathname;
    if (pathname === '/v1/agent/mcp/discoveries' || pathname.startsWith('/v1/agent/mcp/discoveries/')) return phoneDiscovery(request, env, ctx);
    if (pathname === '/v1/agent/mcp' || pathname.startsWith('/v1/agent/mcp/')) return mcpManagement(request, { db: env.DB, phoneDiscovery: true });
    if (new URL(request.url).pathname === '/v1/agent/voice/stream') return upgradeFunAsr(request, env);
    if (new URL(request.url).pathname === '/v1/agent/voice') return voiceInput(request, env, fetch, voiceTokenCache);
    try {
      const route = await parseHttpRequest(request);
      const authorization = request.headers.get('Authorization') ?? '';
      const principal = await createDeviceAuth({ db: env.DB }).authenticate(authorization);
      if (route.kind === 'purge') {
        // 先持久化拒绝标记；DO 暂不可用时仍不能让迟到提交重新创建正文。
        await createCloudTasks({ db: env.DB }).purgeRequest(authorization, route.id);
        const lifetime = createRuntimeLifetime();
        try {
          const complete: Promise<PurgeReply> = env.AGENT_RUNTIME.getByName(principal.ownerId)
            .purgeRequest(authorization, route.id);
          const reply = await lifetime.wait(complete);
          if (!reply.ok) throw new CloudError(reply.error);
          if (reply.requestId !== route.id || reply.purged !== true) throw new CloudError('unavailable');
          return Response.json({ requestId: route.id, purged: true }, { headers: { 'Cache-Control': 'no-store' } });
        } finally { lifetime.close(); }
      }
      if (route.kind === 'events') {
        const runtime = env.AGENT_RUNTIME.getByName(principal.ownerId);
        const load = async (cursor: number) => {
          const reply = await runtime.events(authorization, route.id, cursor);
          if (!reply.ok) throw new CloudError(reply.error);
          return reply.batch;
        };
        await load(route.after);
        // 预检只决定HTTP状态；真正交付仍重读，不能缓存撤权前的首批正文。
        return taskEventsResponse(load, route.after, request.signal);
      }
      if (route.kind === 'mcp') {
        const reply = await env.AGENT_RUNTIME.getByName(principal.ownerId).mcpTask(authorization, route.id, route.action, route.input);
        if (!reply.ok) throw new CloudError(reply.error);
        return Response.json(reply.data, { headers: { 'Cache-Control': 'no-store' } });
      }
      if (route.kind === 'memory') {
        const reply = await env.AGENT_RUNTIME.getByName(principal.ownerId).memory(authorization, route.id, route.reply);
        if (!reply.ok) throw new CloudError(reply.error);
        return Response.json(route.reply ? { accepted: true } : { memory: reply.memory },
          { headers: { 'Cache-Control': 'no-store' } });
      }
      if (route.kind === 'submit') {
        const lifetime = createRuntimeLifetime();
        try {
          let accepted!: (response: Response) => void;
          const receipt = new Promise<Response>(resolve => { accepted = resolve; });
          class ReceiptTarget extends RpcTarget {
            async accepted(task: CloudTaskView): Promise<void> { accepted(taskResponse(task, true)); }
          }
          const complete = Promise.resolve(env.AGENT_RUNTIME.getByName(principal.ownerId)
            .execute(authorization, route.input, new ReceiptTarget(), route.streaming ?? false));
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
      if (route.kind === 'cancel' || route.kind === 'withdraw' || route.kind === 'acknowledge') {
        const lifetime = createRuntimeLifetime();
        const notify = Promise.resolve().then(() => env.AGENT_RUNTIME.getByName(principal.ownerId)
          .interrupt(authorization, route.id, route.kind as 'cancel' | 'withdraw' | 'acknowledge'));
        ctx.waitUntil(lifetime.wait(notify).catch(() => {}).finally(() => lifetime.close()));
      }
      return taskResponse(task);
    } catch (error) { return errorResponse(error); }
  },
} satisfies ExportedHandler<Env>;
