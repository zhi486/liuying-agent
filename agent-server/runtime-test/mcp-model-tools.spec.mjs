import { env } from 'cloudflare:workers';
import { applyD1Migrations } from 'cloudflare:test';
import { beforeAll, expect, it } from 'vitest';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';
import { createMcpDecisions } from '../src/mcp-invocations.ts';
import { createMcpModelTools } from '../src/mcp-model-tools.ts';
import { runAgent } from '../src/loop.ts';
import { parseResult } from '../src/cloud-task-contracts.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
const definition = { name: 'notes.add', inputSchema: { type: 'object', properties: { text: { type: 'string' } },
  required: ['text'], additionalProperties: false } };
async function fixture(mode = 'read') {
  const ownerId = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
  const device = await createDeviceAdmin({ db: env.DB }).issue(ownerId);
  const principal = { ownerId, deviceId: device.deviceId };
  const header = `Bearer ${device.token}`;
  const tasks = createCloudTasks({ db: env.DB });
  const accepted = await tasks.accept(header, { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '记录合成便签', consent: { cloudProcessing: true, runSummaries: false }, summaries: [] });
  const execution = await tasks.claim(header, accepted.task.id);
  const store = createMcpStore({ db: env.DB, principal,
    discover: async () => collectMcpCatalog(async () => ({ tools: [definition] }), new AbortController().signal) });
  const registered = await store.register('https://tools.example.com/mcp');
  const found = await store.discover(registered.id, registered.revision, new AbortController().signal);
  const service = await store.approve(found.id, found.revision, [{ name: definition.name, mode }]);
  const methods = [];
  const options = { db: env.DB, principal, execution, services: [{ serviceId: service.id, revision: service.revision }],
    async send(_dispatch, request) {
      if (request.method === 'GET') return new Response(null, { status: 405 });
      if (request.method === 'DELETE') return new Response(null, { status: 204 });
      const m = await request.json(); methods.push(m.method);
      if (m.method.startsWith('notifications/')) return new Response(null, { status: 202 });
      return Response.json({ jsonrpc: '2.0', id: m.id, result: m.method === 'initialize'
        ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
        : m.method === 'tools/list' ? { tools: [definition] } : { content: [{ type: 'text', text: '合成成功' }] } });
    } };
  const call = { id: 'call_1', name: 'mcp.call', args: { modelName: service.grants[0].modelName,
    argumentsJson: '{"text":"仅用于本地运行时测试"}' } };
  return { options, methods, call, store, service, decisions: createMcpDecisions({ db: env.DB, principal }) };
}

it.each([false, true])('workerd 中模型选择经过 SDK 和真实 D1 单次派发，片段=%s 回执进入第二轮', async truncated => {
  const f = await fixture();
  const mcp = await createMcpModelTools({ ...f.options, async send(dispatch, request) {
    const message = request.method === 'POST' ? await request.clone().json() : null;
    const response = await f.options.send(dispatch, request);
    return truncated && message?.method === 'tools/call' ? Response.json({ jsonrpc: '2.0', id: message.id,
      result: { content: [{ type: 'text', text: '字'.repeat(6000) }] } }) : response;
  } });
  let step = 0;
  const result = await runAgent({ taskId: f.options.execution.taskId, prompt: '记录合成便签',
    consent: { cloudProcessing: true, runSummaries: false }, summaries: [] }, {
    mcp, budget: { async reserve(r) { return { reservationId: r.requestId }; }, async settle() {} },
    model: { async generate(request) {
      step++;
      if (step === 2) {
        const observation = request.messages.find(row => row.role === 'tool').data;
        expect(observation.status).toBe('completed');
        expect(observation.result.truncated).toBe(truncated ? true : undefined);
        expect(new TextEncoder().encode(JSON.stringify(observation.result)).byteLength).toBeLessThanOrEqual(16384);
        expect(request.instructions).toContain('truncated 为 true 表示仅收到部分文本');
      }
      return { usage: { inputTokens: 100, outputTokens: 20 }, decision: step === 1
        ? { kind: 'tools', calls: [f.call] } : { kind: 'final', text: '便签已记录。' } };
    } },
  });
  expect(result.status).toBe('completed');
  expect(parseResult(result)).toEqual(result);
  expect(f.methods.filter(m => m === 'tools/call')).toHaveLength(1);
  const row = await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id = ?')
    .bind(f.options.execution.taskId).first();
  expect(row.status).toBe('completed');
  const reopened = await createMcpModelTools(f.options);
  expect((await reopened.execute(f.call, new AbortController().signal)).status).toBe('unconfirmed');
  expect(f.methods.filter(m => m === 'tools/call')).toHaveLength(1);
});

it('workerd 中副作用未确认不出网，实际批准后撤权也不能派发', async () => {
  const f = await fixture('confirm');
  const mcp = await createMcpModelTools(f.options);
  expect(await mcp.execute(f.call, new AbortController().signal)).toEqual({ status: 'rejected', reason: 'confirmation_required' });
  expect(f.methods).toHaveLength(0);
  const approved = await createMcpModelTools({ ...f.options, async awaitConfirmation(row) {
    await f.decisions.decide(row.id, row.intentDigest, 'approve');
    await f.store.disable(f.service.id, f.service.revision);
  } });
  expect(await approved.execute(f.call, new AbortController().signal)).toEqual({ status: 'rejected', reason: 'not_authorized' });
  expect(f.methods).toHaveLength(0);
});
