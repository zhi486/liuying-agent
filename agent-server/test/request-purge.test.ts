import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test, type TestContext } from 'node:test';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { createExecutionLedger } from '../src/execution-ledger.ts';
import { createD1Budget } from '../src/budget.ts';
import { createUsageReceipts } from '../src/usage-receipts.ts';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { input, rate, start } from './execution-fixture.ts';

const answer = { status: 'completed', answer: '只供清理测试的合成正文', actions: [] };
const deferred = () => {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
};
async function setup(t: TestContext) {
  const database = openBudgetDatabase(':memory:', () => start);
  t.after(database.close);
  const admin = createDeviceAdmin({ db: database.db, now: () => start });
  async function device(owner = 'owner') {
    database.sqlite.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').run(owner);
    const credential = await admin.issue(owner);
    return { ...credential, header: `Bearer ${credential.token}` };
  }
  return { database, device, first: await device(), tasks: createCloudTasks({ db: database.db, now: () => start }) };
}

test('未知请求先清理也持久拒绝迟到提交；重建仓储与重复清理均不留正文', async t => {
  const f = await setup(t); const body = input();
  const expected = { requestId: body.clientRequestId, taskId: null };
  assert.deepEqual(await f.tasks.purgeRequest(f.first.header, body.clientRequestId), expected);
  const before = f.database.sqlite.prepare('SELECT * FROM agent_request_purges').all();
  const restarted = createCloudTasks({ db: f.database.db, now: () => start });
  assert.deepEqual(await restarted.purgeRequest(f.first.header, body.clientRequestId), expected);
  await assert.rejects(restarted.accept(f.first.header, body), /request_expired/);
  await assert.rejects(restarted.findByRequest(f.first.header, body.clientRequestId), /not_found/);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_request_purges').all(), before);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks').get()!.n, 0);
  const stored = JSON.stringify(before);
  assert.equal(stored.includes(body.prompt), false);
  assert.equal(stored.includes(f.first.token), false);
});

test('原设备限定清理，其他账户的同请求键互不影响，查询不泄露别人的任务', async t => {
  const f = await setup(t); const peer = await f.device(); const other = await f.device('other');
  const body = input(); const { task } = await f.tasks.accept(f.first.header, body);
  const execution = await f.tasks.claim(f.first.header, task.id); await f.tasks.finish(execution, answer);
  await assert.rejects(f.tasks.purgeRequest(peer.header, body.clientRequestId), /not_found/);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_request_purges').get()!.n, 0);
  await assert.rejects(f.tasks.get(other.header, task.id), /not_found/);
  await assert.rejects(f.tasks.findByRequest(other.header, body.clientRequestId), /not_found/);
  assert.deepEqual(await f.tasks.purgeRequest(other.header, body.clientRequestId), { requestId: body.clientRequestId, taskId: null });
  assert.equal((await f.tasks.get(f.first.header, task.id)).result?.status, 'completed');
  await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
  await assert.rejects(f.tasks.purgeRequest(peer.header, body.clientRequestId), /not_found/);
  await assert.rejects(f.tasks.accept(peer.header, body), /request_expired/);
  await assert.rejects(f.tasks.purgeRequest('invalid', crypto.randomUUID()), /unauthenticated/);
});

test('运行任务清理保留幂等行并阻止迟到完成、认领和重复接单', async t => {
  const f = await setup(t); const body = input(); const { task } = await f.tasks.accept(f.first.header, body);
  const execution = await f.tasks.claim(f.first.header, task.id);
  assert.deepEqual(await f.tasks.purgeRequest(f.first.header, body.clientRequestId), { requestId: body.clientRequestId, taskId: task.id });
  const row = f.database.sqlite.prepare('SELECT * FROM agent_cloud_tasks WHERE id=?').get(task.id)!;
  assert.equal(row.status, 'cancelled'); assert.equal(row.result_state, 'withdrawn');
  assert.equal(row.result_json, null); assert.equal(row.payload_digest, null);
  await assert.rejects(f.tasks.finish(execution, answer), /inactive/);
  await assert.rejects(f.tasks.claim(f.first.header, task.id), /inactive/);
  await assert.rejects(f.tasks.accept(f.first.header, body), /request_expired/);
  await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_cloud_tasks WHERE id=?').get(task.id), row);
});

test('接单在 D1 前后交错清理，后到提交不新建、先到提交不能完成', async t => {
  for (const phase of ['before', 'after'] as const) {
    const f = await setup(t); const entered = deferred(); const release = deferred(); const body = input();
    const db = interceptD1(f.database.db, async (sql, moment) => {
      if (moment === phase && sql.includes('INSERT INTO agent_cloud_tasks')) { entered.resolve(); await release.promise; }
    });
    const accept = createCloudTasks({ db, now: () => start }).accept(f.first.header, body);
    const observed = accept.then(value => ({ value }), error => ({ error }));
    await entered.promise;
    const purged = await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
    release.resolve(); const result = await observed;
    if (phase === 'before') {
      assert.equal(purged.taskId, null);
      assert.ok('error' in result); assert.match(String(result.error), /request_expired/);
    } else {
      assert.ok('value' in result); assert.equal(purged.taskId, result.value.task.id);
      await assert.rejects(f.tasks.claim(f.first.header, result.value.task.id), /inactive/);
    }
    await assert.rejects(f.tasks.accept(f.first.header, body), /request_expired/);
  }
});

test('清理事务失败同时回滚拒绝标记与正文，重试可以成功', async t => {
  const f = await setup(t); const body = input(); const { task } = await f.tasks.accept(f.first.header, body);
  const execution = await f.tasks.claim(f.first.header, task.id); await f.tasks.finish(execution, answer);
  f.database.sqlite.exec("CREATE TRIGGER fail_purge BEFORE UPDATE OF result_json ON agent_cloud_tasks BEGIN SELECT RAISE(ABORT,'合成故障'); END");
  await assert.rejects(f.tasks.purgeRequest(f.first.header, body.clientRequestId), /unavailable/);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_request_purges').get()!.n, 0);
  assert.deepEqual((await f.tasks.get(f.first.header, task.id)).result, answer);
  f.database.sqlite.exec('DROP TRIGGER fail_purge');
  await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
  assert.equal((await f.tasks.get(f.first.header, task.id)).result, null);
});

test('清理保留预算预留、用量收据和 MCP 审计；迟到用量仍能按原账本结算', async t => {
  const f = await setup(t); const body = input(); const { task } = await f.tasks.accept(f.first.header, body);
  const execution = await f.tasks.claim(f.first.header, task.id);
  const ledger = await createExecutionLedger({ db: f.database.db, execution, now: () => start });
  const budget = createD1Budget({ db: f.database.db, ownerId: 'owner', execution: ledger.binding, rate, now: () => start });
  const signal = new AbortController().signal; const requestId = `${task.id}:1`;
  const reserved = await budget.reserve({ taskId: task.id, requestId, limits: { maxInputTokens: 20000, maxOutputTokens: 600 } }, signal);
  assert.ok(reserved); await ledger.beginDispatch(requestId, reserved.reservationId, signal);
  f.database.sqlite.exec(readFileSync(new URL('../migrations/0006_agent_mcp_invocations.sql', import.meta.url), 'utf8'));
  f.database.sqlite.prepare(`INSERT INTO agent_mcp_invocations
    (id,owner_id,device_id,task_id,execution_hash,call_id,service_id,endpoint,service_revision,catalog_fingerprint,
      model_name,tool_name,mode,arguments_digest,intent_digest,status,created_at,expires_at)
    VALUES (?,'owner',?,?,?,'synthetic_call',?,'https://tools.example.com/mcp',?,?,'mcp_fixture','notes.read','read',?,?,'approved',?,?)`)
    .run(crypto.randomUUID(), f.first.deviceId, task.id, ledger.binding.executionHash, crypto.randomUUID(),
      crypto.randomUUID(), 'a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64), start, start + 30000);
  const invocation = f.database.sqlite.prepare('SELECT * FROM agent_mcp_invocations').all();
  const reservation = f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').all();
  await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').all(), reservation);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_mcp_invocations').all(), invocation);
  const receipts = createUsageReceipts({ db: f.database.db, ownerId: 'owner', execution: ledger.binding });
  await receipts.record(reserved.reservationId, { inputTokens: 100, outputTokens: 40 });
  const rows = f.database.sqlite.prepare('SELECT * FROM agent_usage_receipts').all();
  const settled = f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').all();
  assert.equal(settled[0]!.charged_micros, 78);
  await f.tasks.purgeRequest(f.first.header, body.clientRequestId);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_usage_receipts').all(), rows);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').all(), settled);
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_mcp_invocations').all(), invocation);
  await assert.rejects(ledger.check(signal));
});
