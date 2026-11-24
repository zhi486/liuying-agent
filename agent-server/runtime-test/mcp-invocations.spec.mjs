import { env } from 'cloudflare:workers';
import { applyD1Migrations } from 'cloudflare:test';
import { beforeAll, expect, it } from 'vitest';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { createMcpDecisions, createMcpInvocations } from '../src/mcp-invocations.ts';
import { callMcpHttp } from '../src/mcp-http.ts';
import { interceptD1 } from '../test/d1-interceptor.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
const signal = () => new AbortController().signal;
const tool = { name: 'notes.add', annotations: { readOnlyHint: true }, inputSchema: { type: 'object',
  properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false } };
async function setup(mode = 'confirm') {
  const ownerId = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
  const admin = createDeviceAdmin({ db: env.DB });
  const device = await admin.issue(ownerId);
  const principal = { ownerId, deviceId: device.deviceId };
  const header = `Bearer ${device.token}`;
  const tasks = createCloudTasks({ db: env.DB });
  const accepted = await tasks.accept(header, { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '合成插件测试', consent: { cloudProcessing: true, runSummaries: false }, summaries: [] });
  const execution = await tasks.claim(header, accepted.task.id);
  const store = createMcpStore({ db: env.DB, principal,
    discover: async () => collectMcpCatalog(async () => ({ tools: [tool] }), signal()) });
  const initial = await store.register('https://tools.example.com/mcp');
  const found = await store.discover(initial.id, initial.revision, signal());
  const service = await store.approve(found.id, found.revision, [{ name: tool.name, mode }]);
  const dispatch = { serviceId: service.id, ownerId, endpoint: service.endpoint, revision: service.revision,
    fingerprint: service.catalog.fingerprint, modelName: service.grants[0].modelName, name: tool.name, mode,
    argumentsJson: '{"text":"私密合成参数"}' };
  const options = { db: env.DB, principal, execution };
  return { admin, device, principal, header, tasks, store, service, dispatch, options,
    invocations: await createMcpInvocations(options), decisions: createMcpDecisions({ db: env.DB, principal }) };
}

it('实际 D1 中逐次确认绑定原参数；重建执行器不重发，其他设备不能确认', async () => {
  const f = await setup();
  const pending = await f.invocations.prepare('call_1', f.dispatch, signal());
  expect(pending.status).toBe('pending');
  await expect(f.invocations.claim('call_1', f.dispatch, signal())).rejects.toMatchObject({ code: 'confirmation_required' });
  const sibling = await f.admin.issue(f.principal.ownerId);
  const wrong = createMcpDecisions({ db: env.DB, principal: { ...f.principal, deviceId: sibling.deviceId } });
  await expect(wrong.decide(pending.id, pending.intentDigest, 'approve')).rejects.toMatchObject({ code: 'unavailable' });
  await f.decisions.decide(pending.id, pending.intentDigest, 'approve');
  await expect(f.invocations.claim('call_1', { ...f.dispatch, argumentsJson: '{"text":"改变参数"}' }, signal()))
    .rejects.toMatchObject({ code: 'conflict' });
  const reopened = await createMcpInvocations(f.options);
  await reopened.claim('call_1', f.dispatch, signal());
  await expect(f.invocations.claim('call_1', f.dispatch, signal())).rejects.toMatchObject({ code: 'already_dispatched' });
});

it('实际 D1 并发领取只有一次成功；六个意图的任务上限不被并发突破', async () => {
  const f = await setup('read');
  const prepared = await Promise.allSettled(Array.from({ length: 9 }, (_, i) => f.invocations.prepare(`call_${i}`,
    { ...f.dispatch, argumentsJson: JSON.stringify({ text: `合成 ${i}` }) }, signal())));
  expect(prepared.filter(row => row.status === 'fulfilled')).toHaveLength(6);
  const row = prepared.find(row => row.status === 'fulfilled').value;
  const index = Number(row.callId.split('_')[1]);
  const dispatch = { ...f.dispatch, argumentsJson: JSON.stringify({ text: `合成 ${index}` }) };
  const other = await createMcpInvocations(f.options);
  const claimed = await Promise.allSettled([f.invocations.claim(row.callId, dispatch, signal()), other.claim(row.callId, dispatch, signal())]);
  expect(claimed.filter(row => row.status === 'fulfilled')).toHaveLength(1);
  expect(claimed.filter(row => row.status === 'rejected' && row.reason.code === 'already_dispatched')).toHaveLength(1);
});

it('实际 D1 的最终写入拒绝已停用服务；拒绝确认不会被 prepare 复活', async () => {
  const f = await setup();
  const pending = await f.invocations.prepare('call_1', f.dispatch, signal());
  await f.decisions.decide(pending.id, pending.intentDigest, 'deny');
  expect((await f.invocations.prepare('call_1', f.dispatch, signal())).status).toBe('denied');
  await expect(f.decisions.decide(pending.id, pending.intentDigest, 'approve')).rejects.toMatchObject({ code: 'inactive' });
  const r = await setup('read');
  await r.invocations.prepare('call_1', r.dispatch, signal());
  let changed = false;
  const db = interceptD1(env.DB, async (sql, phase) => {
    if (!changed && phase === 'before' && sql.includes("SET status = 'may_have_dispatched'")) {
      changed = true;
      await r.store.disable(r.service.id, r.service.revision);
    }
  });
  const delayed = await createMcpInvocations({ ...r.options, db });
  await expect(delayed.claim('call_1', r.dispatch, signal())).rejects.toMatchObject({ code: 'inactive' });
  expect((await r.invocations.get('call_1')).status).toBe('approved');
});

it('实际 D1 设备撤销阻止领取，但迟到回执只记摘要，不重新启用任务', async () => {
  const f = await setup('read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  await f.invocations.claim('call_1', f.dispatch, signal());
  await f.admin.revoke(f.principal.ownerId, f.device.deviceId);
  const result = { source: 'mcp', serviceId: f.service.id, modelName: f.dispatch.modelName,
    isError: true, content: [{ type: 'text', text: '私密合成结果' }] };
  await f.invocations.complete('call_1', f.dispatch, result);
  await f.invocations.complete('call_1', f.dispatch, result);
  await expect(f.invocations.complete('call_1', f.dispatch, { ...result, isError: false })).rejects.toMatchObject({ code: 'conflict' });
  const row = await env.DB.prepare('SELECT * FROM agent_mcp_invocations WHERE task_id = ?').bind(f.options.execution.taskId).first();
  expect(row.status).toBe('completed'); expect(row.result_is_error).toBe(1);
  for (const secret of ['私密合成参数', '私密合成结果', f.options.execution.token, f.device.token]) expect(JSON.stringify(row)).not.toContain(secret);
  expect((await env.DB.prepare('SELECT status FROM agent_cloud_tasks WHERE id = ?').bind(row.task_id).first()).status).toBe('cancelled');
  await expect(f.invocations.claim('call_1', f.dispatch, signal())).rejects.toMatchObject({ code: 'unavailable' });
});

it('实际 D1 已提交许可即使回包丢失，持久状态也不能重置', async () => {
  const f = await setup('read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  const db = interceptD1(env.DB, async (sql, phase) => {
    if (phase === 'after' && sql.includes("SET status = 'may_have_dispatched'")) throw new Error('合成数据库回包丢失');
  });
  const delayed = await createMcpInvocations({ ...f.options, db });
  await expect(delayed.claim('call_1', f.dispatch, signal())).rejects.toMatchObject({ code: 'storage_failed' });
  const reopened = await createMcpInvocations(f.options);
  expect((await reopened.get('call_1')).status).toBe('may_have_dispatched');
  await expect(reopened.claim('call_1', f.dispatch, signal())).rejects.toMatchObject({ code: 'already_dispatched' });
});

it('实际 workerd SDK 与持久许可合成联测：发送后断流，不因恢复重发外部动作', async () => {
  const f = await setup('read'); let calls = 0;
  const options = { service: f.service, actor: f.principal.ownerId, revision: f.service.revision,
    modelName: f.dispatch.modelName, arguments: JSON.parse(f.dispatch.argumentsJson), signal: signal(),
    async authorizeDispatch(dispatch, signal) {
      await f.invocations.prepare('call_1', dispatch, signal);
      await f.invocations.claim('call_1', dispatch, signal);
    },
    async send(request) {
      if (request.method === 'GET') return new Response(null, { status: 405 });
      if (request.method === 'DELETE') return new Response(null, { status: 204 });
      const message = await request.json();
      if (message.method.startsWith('notifications/')) return new Response(null, { status: 202 });
      if (message.method === 'tools/call') { calls++; throw new Error('合成发送后断流'); }
      return Response.json({ jsonrpc: '2.0', id: message.id, result: message.method === 'initialize'
        ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
        : { tools: [tool] } });
    },
  };
  await expect(callMcpHttp(options)).rejects.toMatchObject({ dispatchState: 'sent' });
  await expect(callMcpHttp(options)).rejects.toMatchObject({ code: 'authorization_denied', dispatchState: 'not_sent' });
  expect(calls).toBe(1);
});
