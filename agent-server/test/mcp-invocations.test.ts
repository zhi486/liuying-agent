import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test, type TestContext } from 'node:test';
import { fixture, start } from './execution-fixture.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { callMcpHttp, type McpDispatch } from '../src/mcp-http.ts';
import { createMcpDecisions, createMcpInvocations } from '../src/mcp-invocations.ts';

const signal = () => new AbortController().signal;
const tool = { name: 'notes.add', annotations: { readOnlyHint: true }, inputSchema: { type: 'object', properties: {
  text: { type: 'string', minLength: 1 } }, required: ['text'], additionalProperties: false } };
const catalog = () => collectMcpCatalog(async () => ({ tools: [tool] }), signal());
async function setup(t: TestContext, mode: 'read' | 'confirm' = 'confirm') {
  const f = await fixture(t);
  for (const name of ['0005_agent_mcp_services.sql', '0006_agent_mcp_invocations.sql'])
    f.database.sqlite.exec(readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8'));
  const principal = { ownerId: 'owner', deviceId: f.device.deviceId };
  const store = createMcpStore({ db: f.database.db, principal, discover: catalog });
  const registered = await store.register('https://tools.example.com/mcp');
  const found = await store.discover(registered.id, registered.revision, signal());
  const service = await store.approve(found.id, found.revision, [{ name: tool.name, mode }]);
  const dispatch: McpDispatch = { serviceId: service.id, ownerId: principal.ownerId, endpoint: service.endpoint,
    revision: service.revision, fingerprint: service.catalog!.fingerprint, modelName: service.grants[0]!.modelName,
    name: tool.name, mode, argumentsJson: '{"text":"合成便签，不应持久保存正文"}' };
  const options = { db: f.database.db, principal, execution: f.execution };
  const invocations = await createMcpInvocations(options);
  const decisions = createMcpDecisions({ db: f.database.db, principal });
  return { ...f, principal, store, service, dispatch, options, invocations, decisions };
}

test('副作用默认为待确认；批准绑定完整意图，重建执行器后也只能领取一次', async t => {
  const f = await setup(t);
  const prepared = await f.invocations.prepare('call_1', f.dispatch, signal());
  assert.equal(prepared.status, 'pending');
  assert.equal(prepared.expiresAtMillis, start + 30000);
  await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()), { code: 'confirmation_required' });
  const approved = await f.decisions.decide(prepared.id, prepared.intentDigest, 'approve');
  assert.equal(approved.status, 'approved');
  assert.deepEqual(await f.decisions.decide(prepared.id, prepared.intentDigest, 'approve'), approved);
  const reopened = await createMcpInvocations(f.options);
  await reopened.claim('call_1', f.dispatch, signal());
  assert.equal((await f.invocations.get('call_1')).status, 'may_have_dispatched');
  await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()), { code: 'already_dispatched' });
});

test('只读工具不需要逐次批准，但仍使用持久单次派发和原始设备执行绑定', async t => {
  const f = await setup(t, 'read');
  const prepared = await f.invocations.prepare('read_1', f.dispatch, signal());
  assert.equal(prepared.status, 'approved');
  await f.invocations.claim('read_1', f.dispatch, signal());
  const restarted = await createMcpInvocations(f.options);
  await assert.rejects(restarted.claim('read_1', f.dispatch, signal()), { code: 'already_dispatched' });
});

test('参数、工具范围、模式和身份不可伪造；同调用 ID 改参数与换 ID 重复动作均不新建', async t => {
  const f = await setup(t);
  const first = await f.invocations.prepare('call_1', f.dispatch, signal());
  assert.deepEqual(await f.invocations.prepare('call_1', f.dispatch, signal()), first);
  for (const change of [{ argumentsJson: '{"text":"另一段正文"}' }, { ownerId: 'other' }, { mode: 'read' as const },
    { name: 'different' }, { fingerprint: '0'.repeat(64) }, { endpoint: 'https://other.example.com/mcp' },
    { argumentsJson: '{ "text": "不接受非规范快照" }' }, { argumentsJson: '{"text":1}' }]) {
    await assert.rejects(f.invocations.prepare('call_1', { ...f.dispatch, ...change }, signal()));
    await assert.rejects(f.invocations.claim('call_1', { ...f.dispatch, ...change }, signal()));
  }
  await assert.rejects(f.invocations.prepare('different_id', f.dispatch, signal()), { code: 'conflict' });
  await assert.rejects(f.decisions.decide(first.id, '0'.repeat(64), 'approve'), { code: 'conflict' });
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_mcp_invocations').get()!.n, 1);
  assert.equal((await f.invocations.get('call_1')).status, 'pending');
});

test('拒绝是终态，重复拒绝可核对；迟到批准、重新 prepare 或远端只读注解不能复活', async t => {
  const f = await setup(t);
  const prepared = await f.invocations.prepare('call_1', f.dispatch, signal());
  const denied = await f.decisions.decide(prepared.id, prepared.intentDigest, 'deny');
  assert.equal(denied.status, 'denied');
  assert.deepEqual(await f.decisions.decide(prepared.id, prepared.intentDigest, 'deny'), denied);
  await assert.rejects(f.decisions.decide(prepared.id, prepared.intentDigest, 'approve'), { code: 'inactive' });
  assert.equal((await f.invocations.prepare('call_1', f.dispatch, signal())).status, 'denied');
  await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()), { code: 'inactive' });
});

test('同 owner 另一台设备和不同 owner 不能读取、确认或借用执行权', async t => {
  const f = await setup(t);
  const prepared = await f.invocations.prepare('call_1', f.dispatch, signal());
  const sibling = await f.admin.issue('owner');
  f.database.sqlite.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').run('other');
  const other = await f.admin.issue('other');
  for (const principal of [{ ownerId: 'owner', deviceId: sibling.deviceId }, { ownerId: 'other', deviceId: other.deviceId }]) {
    const decisions = createMcpDecisions({ db: f.database.db, principal });
    await assert.rejects(decisions.get(prepared.id), { code: 'unavailable' });
    await assert.rejects(decisions.decide(prepared.id, prepared.intentDigest, 'approve'));
    await assert.rejects(createMcpInvocations({ ...f.options, principal }));
  }
  await assert.rejects(createMcpInvocations({ ...f.options, execution: { ...f.execution, token: '0'.repeat(64) } }));
});

test('批准后停用、换端点、改目录、移除或变更授权，均阻止后续领取许可', async t => {
  for (const action of ['disable', 'replaceEndpoint', 'invalidate', 'remove', 'approve'] as const) {
    const f = await setup(t);
    const prepared = await f.invocations.prepare('call_1', f.dispatch, signal());
    await f.decisions.decide(prepared.id, prepared.intentDigest, 'approve');
    if (action === 'replaceEndpoint') await f.store.replaceEndpoint(f.service.id, f.service.revision, 'https://other.example.com/mcp');
    else if (action === 'approve') await f.store.approve(f.service.id, f.service.revision, [{ name: tool.name, mode: 'read' }]);
    else await f.store[action](f.service.id, f.service.revision);
    await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()));
    assert.equal((await f.invocations.get('call_1')).status, 'approved');
  }
});

test('数据库执行前任务取消、设备撤销或过期；旧页面与异步检查都不能扩大有效期', async t => {
  for (const action of ['cancel', 'revoke', 'expire'] as const) {
    const f = await setup(t);
    const prepared = await f.invocations.prepare('call_1', f.dispatch, signal());
    await f.decisions.decide(prepared.id, prepared.intentDigest, 'approve');
    if (action === 'cancel') await f.tasks.cancel(f.header, f.execution.taskId);
    if (action === 'revoke') await f.admin.revoke('owner', f.device.deviceId);
    if (action === 'expire') f.setTime(start + 30000);
    await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()));
  }
});

test('确认与领取在最终 SQL 执行时核对截止，读取后过期也不能批准或领取', async t => {
  const f = await setup(t);
  const first = await f.invocations.prepare('call_1', f.dispatch, signal());
  let delay = false;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (delay && phase === 'before' && sql.includes('UPDATE agent_mcp_invocations')) f.setTime(start + 30000);
  });
  const decisions = createMcpDecisions({ db, principal: f.principal });
  delay = true;
  await assert.rejects(decisions.decide(first.id, first.intentDigest, 'approve'), { code: 'inactive' });
  assert.equal((await f.invocations.get('call_1')).status, 'pending');
});

test('两个执行器并发争抢同一许可，实际 SQL 只让一个成功', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  const other = await createMcpInvocations(f.options);
  const results = await Promise.allSettled([f.invocations.claim('call_1', f.dispatch, signal()), other.claim('call_1', f.dispatch, signal())]);
  assert.equal(results.filter(row => row.status === 'fulfilled').length, 1);
  assert.equal(results.filter(row => row.status === 'rejected' && row.reason.code === 'already_dispatched').length, 1);
});

test('授权快照在检查后被原地改写，最终 SQL 仍拒绝领取；存储错误不暴露内部信息', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  let changed = false;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (!changed && phase === 'before' && sql.includes("SET status = 'may_have_dispatched'")) {
      changed = true;
      // 即使错误宿主没有更换 revision，完整快照比较也不能放过目录／权限变化。
      f.database.sqlite.prepare("UPDATE agent_mcp_services SET grants_json = '[]' WHERE id = ?").run(f.service.id);
    }
  });
  const late = await createMcpInvocations({ ...f.options, db });
  await assert.rejects(late.claim('call_1', f.dispatch, signal()), { code: 'inactive' });
  assert.equal((await f.invocations.get('call_1')).status, 'approved');
  const broken = interceptD1(f.database.db, async () => { throw new Error('私密 SQL 连接信息'); });
  await assert.rejects(createMcpInvocations({ ...f.options, db: broken }), error =>
    error instanceof Error && 'code' in error && error.code === 'storage_failed' && !error.message.includes('SQL'));
});

test('领取 SQL 提交成功但回包丢失，重新查询仅核对旧状态，不恢复发送权', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'after' && sql.includes("SET status = 'may_have_dispatched'")) throw new Error('合成数据库回包丢失');
  });
  const late = await createMcpInvocations({ ...f.options, db });
  await assert.rejects(late.claim('call_1', f.dispatch, signal()), { code: 'storage_failed' });
  const reopened = await createMcpInvocations(f.options);
  assert.equal((await reopened.get('call_1')).status, 'may_have_dispatched');
  await assert.rejects(reopened.claim('call_1', f.dispatch, signal()), { code: 'already_dispatched' });
});

test('取消发生在领取写入后回包前，保留可能已派发，不能因客户端未拿到许可而重发', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  const controller = new AbortController();
  let delay = false;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (delay && phase === 'after' && sql.includes("SET status = 'may_have_dispatched'")) controller.abort();
  });
  const late = await createMcpInvocations({ ...f.options, db });
  delay = true;
  await assert.rejects(late.claim('call_1', f.dispatch, controller.signal));
  assert.equal((await f.invocations.get('call_1')).status, 'may_have_dispatched');
  await assert.rejects(f.invocations.claim('call_1', f.dispatch, signal()), { code: 'already_dispatched' });
});

test('每任务最多六个不同意图；并发 prepare 和重复事件不能突破或重置计数', async t => {
  const f = await setup(t, 'read');
  const results = await Promise.allSettled(Array.from({ length: 9 }, (_, i) => f.invocations.prepare(`call_${i}`,
    { ...f.dispatch, argumentsJson: JSON.stringify({ text: `合成 ${i}` }) }, signal())));
  assert.equal(results.filter(row => row.status === 'fulfilled').length, 6);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_mcp_invocations').get()!.n, 6);
});

test('完成回执只存摘要；撤销后迟到回执可记账但不能重启任务或恢复发送权', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  await f.invocations.claim('call_1', f.dispatch, signal());
  await f.admin.revoke('owner', f.device.deviceId);
  const observation = { source: 'mcp' as const, serviceId: f.service.id, modelName: f.dispatch.modelName,
    isError: true, content: [{ type: 'text' as const, text: '私密上游返回，不应保存正文' }] };
  await f.invocations.complete('call_1', f.dispatch, observation);
  await f.invocations.complete('call_1', f.dispatch, observation);
  await assert.rejects(f.invocations.complete('call_1', f.dispatch, { ...observation, isError: false }), { code: 'conflict' });
  const row = f.database.sqlite.prepare('SELECT * FROM agent_mcp_invocations').get()!;
  assert.equal(row.status, 'completed');
  assert.equal(row.result_is_error, 1);
  for (const secret of ['私密上游返回', '合成便签', f.execution.token, f.device.token]) assert.equal(JSON.stringify(row).includes(secret), false);
  assert.equal(f.database.sqlite.prepare('SELECT status FROM agent_cloud_tasks').get()!.status, 'cancelled');
});

test('片段回执严格校验标记和预算，完整性变化不能覆写原摘要', async t => {
  const f = await setup(t, 'read');
  await f.invocations.prepare('call_1', f.dispatch, signal());
  await f.invocations.claim('call_1', f.dispatch, signal());
  const result = { source: 'mcp' as const, serviceId: f.service.id, modelName: f.dispatch.modelName,
    isError: false, content: [{ type: 'text' as const, text: '有界片段' }] };
  for (const truncated of [false, 'true', 1, null]) await assert.rejects(f.invocations.complete('call_1', f.dispatch,
    { ...result, truncated } as unknown as Parameters<typeof f.invocations.complete>[2]), { code: 'invalid_request' });
  await assert.rejects(f.invocations.complete('call_1', f.dispatch, { ...result, truncated: true,
    content: [{ type: 'text', text: '字'.repeat(6000) }] }), { code: 'invalid_request' });
  await f.invocations.complete('call_1', f.dispatch, { ...result, truncated: true });
  await f.invocations.complete('call_1', f.dispatch, { ...result, truncated: true });
  await assert.rejects(f.invocations.complete('call_1', f.dispatch, result), { code: 'conflict' });
});

test('与底层调用器联测：只读许可真实调用一次；重建、断流和恢复均不能再发送', async t => {
  const f = await setup(t, 'read');
  let calls = 0;
  const options = { service: f.service, actor: 'owner', revision: f.service.revision, modelName: f.dispatch.modelName,
    arguments: JSON.parse(f.dispatch.argumentsJson), signal: signal(), timeoutMs: 1000,
    async authorizeDispatch(dispatch: McpDispatch, signal: AbortSignal) {
      await f.invocations.prepare('call_1', dispatch, signal);
      await f.invocations.claim('call_1', dispatch, signal);
    },
    async send(request: Request) {
      if (request.method === 'GET') return new Response(null, { status: 405 });
      if (request.method === 'DELETE') return new Response(null, { status: 204 });
      const message = await request.json() as { id: number; method: string };
      if (message.method.startsWith('notifications/')) return new Response(null, { status: 202 });
      if (message.method === 'tools/call') { calls++; throw new Error('合成发送后断流'); }
      return Response.json({ jsonrpc: '2.0', id: message.id, result: message.method === 'initialize'
        ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
        : { tools: [tool] } });
    },
  };
  await assert.rejects(callMcpHttp(options), { dispatchState: 'sent' });
  assert.equal((await f.invocations.get('call_1')).status, 'may_have_dispatched');
  await assert.rejects(callMcpHttp(options), { code: 'authorization_denied', dispatchState: 'not_sent' });
  assert.equal(calls, 1);
});
