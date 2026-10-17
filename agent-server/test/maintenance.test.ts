import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test, type TestContext } from 'node:test';
import { fixture, input, rate, start } from './execution-fixture.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { createD1Budget } from '../src/budget.ts';
import { createExecutionLedger } from '../src/execution-ledger.ts';
import { runMaintenance } from '../src/maintenance.ts';
import type { BudgetDatabase, BudgetStatement } from '../src/d1.ts';

async function pending(t: TestContext, count: number) {
  const f = await fixture(t);
  const ids: string[] = [];
  for (let index = 0; index < count; index++) {
    const execution = index === 0 ? f.execution
      : await f.tasks.claim(f.header, (await f.tasks.accept(f.header, input())).task.id);
    const ledger = await createExecutionLedger({ db: f.database.db, execution, now: f.now });
    const budget = createD1Budget({ db: f.database.db, ownerId: 'owner', rate,
      execution: ledger.binding, now: f.now });
    const requestId = `${execution.taskId}:1`;
    const reserved = await budget.reserve({ taskId: execution.taskId, requestId,
      limits: { maxInputTokens: 20000, maxOutputTokens: 600 } }, new AbortController().signal);
    assert.ok(reserved);
    await ledger.beginDispatch(requestId, reserved.reservationId, new AbortController().signal);
    ids.push(reserved.reservationId);
    await f.tasks.cancel(f.header, execution.taskId);
  }
  const receipt = (id: string) => f.database.sqlite.prepare(
    'INSERT INTO agent_usage_receipts VALUES (?,100,40,?,0)',
  ).run(id, f.now());
  const state = () => f.database.sqlite.prepare('SELECT * FROM agent_maintenance_state').get()!;
  return { ...f, ids: ids.sort(), receipt, state };
}

test('维护游标持久翻页，前五条未知费用不挡住后页，当前费率过期仍结算旧收据', async t => {
  const f = await pending(t, 6);
  f.receipt(f.ids[5]!);
  f.setTime(start + 86400001);
  const first = await runMaintenance({ db: f.database.db, now: f.now });
  assert.equal(first.processed, 5);
  assert.equal(first.receiptsApplied, 0);
  assert.equal(f.state().cursor, f.ids[4]);
  assert.equal(f.state().revision, 1);
  const second = await runMaintenance({ db: f.database.db, now: f.now });
  assert.equal(second.processed, 1);
  assert.equal(second.receiptsApplied, 1);
  assert.equal(f.state().cursor, '');
  assert.equal(f.state().revision, 2);
  assert.equal(f.database.sqlite.prepare('SELECT SUM(charged_micros) n FROM agent_budget_reservations').get()!.n, 78);
  assert.equal(f.database.sqlite.prepare("SELECT COUNT(*) n FROM agent_budget_reservations WHERE state='uncertain' AND charged_micros IS NULL").get()!.n, 5);
  assert.equal((await runMaintenance({ db: f.database.db, now: f.now })).receiptsApplied, 0);
});

test('维护清空到期内容并保留去重任务，重复应用新迁移不重置游标或旧记录', async t => {
  const f = await fixture(t);
  await f.tasks.finish(f.execution, { status: 'completed', answer: '测试私密结果', actions: [] });
  const saved = f.database.sqlite.prepare('SELECT * FROM agent_cloud_tasks').all();
  f.database.sqlite.exec("UPDATE agent_maintenance_state SET cursor='saved-position',revision=3");
  f.database.sqlite.exec(readFileSync(new URL('../migrations/0004_agent_maintenance.sql', import.meta.url), 'utf8'));
  assert.equal(f.database.sqlite.prepare('SELECT cursor FROM agent_maintenance_state').get()!.cursor, 'saved-position');
  assert.deepEqual(f.database.sqlite.prepare('SELECT * FROM agent_cloud_tasks').all(), saved);
  f.setTime(start + 86400001);
  await runMaintenance({ db: f.database.db, now: f.now });
  const row = f.database.sqlite.prepare('SELECT * FROM agent_cloud_tasks').get()!;
  assert.equal(row.status, 'completed');
  assert.equal(row.result_state, 'expired');
  assert.equal(row.result_json, null);
  assert.equal(row.payload_digest, null);
  assert.equal(row.id, f.execution.taskId);
});

test('满页收据维护含游标和清理总计41条SQL，重复维护不重复结算', async t => {
  const f = await pending(t, 5);
  f.ids.forEach(f.receipt);
  let queries = 0;
  const originals = new WeakMap<BudgetStatement, BudgetStatement>();
  const wrap = (original: BudgetStatement): BudgetStatement => {
    const result: BudgetStatement = {
      bind: (...values) => wrap(original.bind(...values)),
      all: () => { queries++; return original.all(); },
      run: () => { queries++; return original.run(); },
    };
    originals.set(result, original);
    return result;
  };
  const db: BudgetDatabase = {
    prepare: sql => wrap(f.database.db.prepare(sql)),
    batch: statements => {
      queries += statements.length;
      return f.database.db.batch(statements.map(statement => originals.get(statement)!));
    },
  };
  const result = await runMaintenance({ db, now: f.now });
  assert.equal(result.receiptsApplied, 5);
  assert.equal(queries, 41);
  assert.equal((await runMaintenance({ db, now: f.now })).receiptsApplied, 0);
  assert.equal(f.database.sqlite.prepare('SELECT SUM(charged_micros) n FROM agent_budget_reservations').get()!.n, 390);
});

test('账务故障不推进游标、不暴露原始异常，过期指纹仍先清理', async t => {
  const f = await pending(t, 1);
  f.setTime(start + 86400001);
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.includes('LEFT JOIN agent_usage_receipts'))
      throw new Error('测试模拟秘密，禁止输出');
  });
  await assert.rejects(runMaintenance({ db, now: f.now }), { message: 'maintenance_unavailable' });
  assert.equal(f.state().revision, 0);
  assert.equal(f.state().cursor, '');
  assert.equal(f.database.sqlite.prepare('SELECT payload_digest FROM agent_cloud_tasks').get()!.payload_digest, null);
});

test('两个真正重叠的维护调用不回退游标、不重复结算', { timeout: 5000 }, async t => {
  const f = await pending(t, 6);
  f.ids.forEach(f.receipt);
  let entered!: () => void;
  let release!: () => void;
  const paused = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.startsWith('UPDATE agent_maintenance_state')) {
      entered();
      await gate;
    }
  });
  const slow = runMaintenance({ db, now: f.now });
  try {
    await paused;
    const fast = await runMaintenance({ db: f.database.db, now: f.now });
    assert.equal(fast.processed, 1);
    assert.equal(fast.cursorSaved, true);
  } finally { release(); }
  const result = await slow;
  assert.equal(result.receiptsApplied, 5);
  assert.equal(result.cursorSaved, false);
  assert.equal(f.state().cursor, '');
  assert.equal(f.state().revision, 1);
  assert.equal(f.database.sqlite.prepare('SELECT SUM(charged_micros) n FROM agent_budget_reservations').get()!.n, 468);
  assert.equal((await runMaintenance({ db: f.database.db, now: f.now })).processed, 0);
});

test('页内前一笔已结算后一笔失败，保留游标，下一次只补未完成收据', async t => {
  const f = await pending(t, 2);
  f.ids.forEach(f.receipt);
  let settlements = 0;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.includes("SET state = 'settled', charged_micros") && ++settlements === 2)
      throw new Error('模拟页内第二笔故障');
  });
  await assert.rejects(runMaintenance({ db, now: f.now }), { message: 'maintenance_unavailable' });
  assert.equal(f.state().revision, 0);
  assert.equal(f.state().cursor, '');
  assert.equal(f.database.sqlite.prepare('SELECT SUM(charged_micros) n FROM agent_budget_reservations').get()!.n, 78);
  assert.equal((await runMaintenance({ db: f.database.db, now: f.now })).receiptsApplied, 1);
  assert.equal(f.database.sqlite.prepare('SELECT SUM(charged_micros) n FROM agent_budget_reservations').get()!.n, 156);
});

test('游标写入失败可重放已结算收据但不重复收费', async t => {
  const f = await pending(t, 1);
  f.receipt(f.ids[0]!);
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.startsWith('UPDATE agent_maintenance_state')) throw new Error('模拟写失败');
  });
  await assert.rejects(runMaintenance({ db, now: f.now }), { message: 'maintenance_unavailable' });
  assert.equal(f.state().revision, 0);
  assert.equal((await runMaintenance({ db: f.database.db, now: f.now })).receiptsApplied, 0);
  assert.equal(f.database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!.charged_micros, 78);
});
