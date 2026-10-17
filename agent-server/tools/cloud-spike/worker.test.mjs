import assert from 'node:assert/strict';
import { test } from 'node:test';
import worker from './worker.mjs';
import { agentProbe } from './agent-probe.mjs';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';

const token = 'fixture-only-not-a-secret-32-characters';
function fixture(t) {
  const database = openBudgetDatabase();
  t.after(() => database.close());
  return { database, env: { DB: database.db, SPIKE_TOKEN: token,
    EXPIRES_AT: String(Date.now() + 60000) } };
}
const request = (path, authorization = `Bearer ${token}`, method = 'POST') =>
  new Request(`https://spike.invalid${path}`, { method, headers: { authorization } });

test('隔离计数端口拒绝超限后不得绕回固定100计数', async (t) => {
  const { env,database } = fixture(t);
  const pending = [];
  const response = await agentProbe(env.DB,'one',{waitUntil:p=>pending.push(p)}, {
    countInputTokens:async()=>20001, tokenCounting:'fixture-hash-bound',
  });
  await Promise.all(pending);
  const {taskId} = await response.json();
  const task = database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').get(taskId);
  assert.equal(task.status,'failed');
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_usage_receipts').get().n,0);
});

// 删除鉴权、到期或路由限制，应使这些用例失败；拒绝请求不得进入 D1。
test('缺密钥、错密钥和超长密钥均拒绝，不访问数据库', async () => {
  const db = { prepare() { assert.fail('拒绝请求不得访问数据库'); } };
  for (const [expected, given] of [[undefined, token], [token, 'wrong'], [token, 'x'.repeat(2000)]]) {
    const response = await worker.fetch(request('/quota/seq-50', `Bearer ${given}`),
      { DB: db, SPIKE_TOKEN: expected, EXPIRES_AT: String(Date.now() + 60000) });
    assert.equal(response.status, expected ? 401 : 503);
  }
});

// 流取消若误取消后台任务，或提前完整返回，以下断言会失败。
test('响应流中断后生产任务仍完成且只结算一次', async (t) => {
  const { env, database } = fixture(t);
  const pending = [];
  const response = await worker.fetch(request('/agent/disconnect'), env, {
    waitUntil(promise) { pending.push(promise); },
  });
  assert.equal(response.status, 200);
  const reader = response.body.getReader();
  const first = await reader.read();
  assert.equal(first.done, false);
  const result = JSON.parse(new TextDecoder().decode(first.value));
  assert.equal(result.transportEntered, true);
  assert.equal(database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?')
    .get(result.taskId).status, 'running');
  await reader.cancel('客户端主动中断');
  await Promise.all(pending);
  assert.equal(database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?')
    .get(result.taskId).status, 'completed');
  const rows = database.sqlite.prepare(`SELECT r.state,r.charged_micros,u.applied
    FROM agent_budget_reservations r JOIN agent_usage_receipts u ON r.id=u.reservation_id
    WHERE r.task_id=?`).all(result.taskId);
  assert.deepEqual(rows.map(row => ({ ...row })), [{ state:'settled', charged_micros:78, applied:1 }]);
});

// 漏保存收据、提前结算、恢复重派模型或重复扣费，都会使此用例失败。
test('收据落库后结算失败由后续请求恢复，重复恢复不重扣费', async (t) => {
  const { env, database } = fixture(t);
  const pending = [];
  const response = await worker.fetch(request('/agent/receipt-fault'), env, {
    waitUntil(promise) { pending.push(promise); },
  });
  assert.equal(response.status, 200);
  const result = await response.json();
  await Promise.all(pending);
  const task = database.sqlite.prepare('SELECT status,reason,result_json FROM agent_cloud_tasks WHERE id=?');
  const before = task.get(result.taskId);
  assert.equal(before.status, 'failed');
  assert.equal(before.reason, 'model_unavailable');
  const query = database.sqlite.prepare(`SELECT r.state,r.charged_micros,u.applied
    FROM agent_budget_reservations r JOIN agent_usage_receipts u ON r.id=u.reservation_id
    WHERE r.task_id=?`);
  assert.deepEqual({ ...query.get(result.taskId) }, { state:'uncertain', charged_micros:null, applied:0 });
  for (const expected of [1, 0]) {
    const recovered = await worker.fetch(request('/agent/recover'), env);
    assert.equal(recovered.status, 200);
    assert.deepEqual(await recovered.json(), { processed:expected, receiptsApplied:expected,
      closed:0, uncertain:0, nextCursor:null });
  }
  assert.deepEqual(task.get(result.taskId), before);
  assert.deepEqual({ ...query.get(result.taskId) }, { state:'settled', charged_micros:78, applied:1 });
});
test('缺失、非法和已到期配置不运行探针', async () => {
  for (const expiry of [undefined, 'not-a-date', '0', String(Date.now() - 1)]) {
    const response = await worker.fetch(request('/quota/seq-50'), {
      SPIKE_TOKEN: token, EXPIRES_AT: expiry,
      DB: { prepare() { assert.fail('过期请求不得访问数据库'); } },
    });
    assert.equal(response.status, 410);
  }
});
test('只允许固定路径、POST 且无查询参数', async (t) => {
  const { env } = fixture(t);
  for (const req of [request('/quota/seq-100000'), request('/quota/seq-50?n=100000'),
    request('/quota/seq-50', `Bearer ${token}`, 'GET')]) {
    assert.equal((await worker.fetch(req, env)).status, 404);
  }
});
// 真实 SQLite 执行固定 SQL；结果中的次数证明探针请求形状，不模拟云配额。
for (const [path, calls, sql] of [
  ['seq-50', 50, 50], ['seq-51', 51, 51], ['batch-51', 1, 51], ['mixed-49-2', 50, 51],
]) test(`配额探针 ${path} 执行有界且准确的查询形状`, async (t) => {
  const { env } = fixture(t);
  const response = await worker.fetch(request(`/quota/${path}`), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { case: path, attemptedCalls: calls,
    completedCalls: calls, completedSql: sql, outcome: 'completed' });
});
test('第51次查询异常保留50次完成证据，不重试或再查数据库', async (t) => {
  const { env } = fixture(t);
  const original = env.DB;
  let issued = 0;
  env.DB = { prepare(sql) { const statement = original.prepare(sql); return {
    async all() { if (++issued === 51) throw new Error('fixture quota'); return statement.all(); },
  }; } };
  const response = await worker.fetch(request('/quota/seq-51'), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { case: 'seq-51', attemptedCalls: 51,
    completedCalls: 50, completedSql: 50, outcome: 'database_error', error: 'fixture quota' });
  assert.equal(issued, 51);
});

// 若错误地启用真实传输、漏写结果/收据或漏结算，实际 SQLite 账务断言必须失败。
for (const [name, rounds] of [['one', 1], ['three', 3], ['background', 1]])
test(`假模型 ${name} 完成生产任务及逐轮收据`, async (t) => {
  const { env, database } = fixture(t);
  const pending = [];
  const response = await worker.fetch(request(`/agent/${name}`), env, {
    waitUntil(promise) { pending.push(promise); },
  });
  assert.equal(response.status, name === 'background' ? 202 : 200);
  const result = await response.json();
  assert.ok(!JSON.stringify(result).includes('lyd_'));
  await Promise.all(pending);
  const task = database.sqlite.prepare('SELECT status,result_state,result_json FROM agent_cloud_tasks WHERE id=?')
    .get(result.taskId);
  assert.equal(task.status, 'completed');
  assert.equal(task.result_state, 'available');
  const saved = JSON.parse(task.result_json);
  assert.equal(saved.answer, '虚构云验证：根据 run_1，你完成了四公里。');
  assert.equal(saved.actions.filter(action => action.kind === 'model_requested').length, rounds);
  assert.equal(saved.actions.filter(action => action.kind === 'tool_completed').length, rounds - 1);
  const rows = database.sqlite.prepare(`SELECT r.state,r.charged_micros,r.input_tokens,r.output_tokens,
    u.applied FROM agent_budget_reservations r LEFT JOIN agent_usage_receipts u ON r.id=u.reservation_id
    WHERE r.task_id=? ORDER BY r.request_id`).all(result.taskId);
  assert.deepEqual(rows.map(row => ({...row})), Array.from({length:rounds}, () => ({
    state:'settled', charged_micros:78, input_tokens:100, output_tokens:40, applied:1,
  })));
});
