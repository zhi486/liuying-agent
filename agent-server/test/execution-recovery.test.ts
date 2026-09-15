import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, unlinkSync, rmdirSync, readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fixture, rate } from './execution-fixture.ts';
import { openBudgetDatabase } from './sqlite-d1.ts';
import * as ledgerModule from '../src/execution-ledger.ts';
import { createD1Budget } from '../src/budget.ts';
import { createUsageReceipts } from '../src/usage-receipts.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';

test('0003 增量迁移保留既有任务、设备、熔断与账单，旧账没有派发证明', () => {
  const sqlite = new DatabaseSync(':memory:');
  try {
    sqlite.exec('PRAGMA foreign_keys = ON');
    for (const name of ['0001_agent_budget.sql', '0002_agent_device_tasks.sql'])
      sqlite.exec(readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8'));
    sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',10000,1)");
    sqlite.exec(`INSERT INTO agent_budget_reservations VALUES
      ('old','owner','task','task:1','2026-09','deepseek-flash','rate',300000,1200000,6720,'settled',42,100,10,0,1000)`);
    const device = crypto.randomUUID();
    sqlite
      .prepare(
        'INSERT INTO agent_devices(id,owner_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?)',
      )
      .run(device, 'owner', 'a'.repeat(64), 1000, 1000 + 7776000000);
    sqlite
      .prepare(
        `INSERT INTO agent_cloud_tasks
      (id,owner_id,device_id,client_request_id,payload_digest,payload_expires_at,status,accepted_at,deadline_at,updated_at)
      VALUES (?,?,?,?,?,?,'queued',1000,31000,1000)`,
      )
      .run(crypto.randomUUID(), 'owner', device, crypto.randomUUID(), 'b'.repeat(64), 86401000);
    const tables = ['agent_budget_accounts', 'agent_devices', 'agent_cloud_tasks'];
    const before = tables.map((name) => sqlite.prepare(`SELECT * FROM ${name}`).all());
    const old = sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
    sqlite.exec(
      readFileSync(new URL('../migrations/0003_agent_execution.sql', import.meta.url), 'utf8'),
    );
    assert.deepEqual(
      tables.map((name) => sqlite.prepare(`SELECT * FROM ${name}`).all()),
      before,
    );
    const row = sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
    for (const key of Object.keys(old)) assert.equal(row[key], old[key]);
    assert.equal(row.execution_hash, null);
    assert.equal(row.dispatch_state, null);
    assert.equal(row.rate_expires_at_millis, null);
  } finally {
    sqlite.close();
  }
});

test('维护入口拒绝超过单批 100 条或畸形游标', async (t) => {
  const f = await fixture(t);
  for (const limit of [0, 101, 1.5])
    await assert.rejects(ledgerModule.recoverExecutions({ db: f.database.db, now: f.now, limit }));
  await assert.rejects(ledgerModule.recoverExecutions({ db: f.database.db, cursor: 'bad\n' }));
});

test('恢复只释放失效执行的 prepared，已派发与旧账保留，分页不启动任务', async (t) => {
  const recover = ledgerModule.recoverExecutions;
  const f = await fixture(t);
  const ledger = await ledgerModule.createExecutionLedger({
    db: f.database.db,
    execution: f.execution,
    now: f.now,
  });
  const budget = createD1Budget({
    db: f.database.db,
    ownerId: 'owner',
    rate,
    now: f.now,
    execution: ledger.binding,
  });
  const ids: string[] = [];
  for (const step of [1, 2]) {
    const requestId = `${f.execution.taskId}:${step}`;
    const r = await budget.reserve(
      {
        taskId: f.execution.taskId,
        requestId,
        limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
      },
      new AbortController().signal,
    );
    assert.ok(r);
    ids.push(r.reservationId);
    if (step === 2)
      await ledger.beginDispatch(requestId, r.reservationId, new AbortController().signal);
  }
  const legacy = createD1Budget({ db: f.database.db, ownerId: 'owner', rate, now: f.now });
  const old = await legacy.reserve(
    { taskId: 'old', requestId: 'old:1', limits: { maxInputTokens: 20000, maxOutputTokens: 600 } },
    new AbortController().signal,
  );
  assert.ok(old);
  const runRecovery = recover;
  await runRecovery({ db: f.database.db, now: f.now });
  assert.equal(
    f.database.sqlite
      .prepare('SELECT state FROM agent_budget_reservations WHERE id = ?')
      .get(ids[0]!)!.state,
    'reserved',
  );
  await f.tasks.cancel(f.header, f.execution.taskId);
  const first = await runRecovery({ db: f.database.db, now: f.now, limit: 1 });
  assert.equal(first.processed, 1);
  assert.ok(first.nextCursor);
  const next = await runRecovery({
    db: f.database.db,
    now: f.now,
    limit: 1,
    cursor: first.nextCursor,
  });
  assert.equal(next.processed, 1);
  assert.equal(next.nextCursor, null);
  assert.equal(
    f.database.sqlite
      .prepare('SELECT state FROM agent_budget_reservations WHERE id = ?')
      .get(ids[0]!)!.state,
    'released',
  );
  assert.equal(
    f.database.sqlite
      .prepare('SELECT state FROM agent_budget_reservations WHERE id = ?')
      .get(ids[1]!)!.state,
    'uncertain',
  );
  assert.equal(
    f.database.sqlite
      .prepare('SELECT state FROM agent_budget_reservations WHERE id = ?')
      .get(old.reservationId)!.state,
    'reserved',
  );
  await assert.rejects(
    ledger.beginDispatch(`${f.execution.taskId}:1`, ids[0]!, new AbortController().signal),
  );
});

test('收据已提交但结算失败，文件库重开后只重放费用，取消结果不复活', async (t) => {
  const recover = ledgerModule.recoverExecutions;
  const directory = mkdtempSync(join(tmpdir(), 'liuying-receipt-'));
  const path = join(directory, 'ledger.sqlite');
  const f = await fixture(t, path);
  const ledger = await ledgerModule.createExecutionLedger({
    db: f.database.db,
    execution: f.execution,
    now: f.now,
  });
  const budget = createD1Budget({
    db: f.database.db,
    ownerId: 'owner',
    rate,
    now: f.now,
    execution: ledger.binding,
  });
  const requestId = `${f.execution.taskId}:1`;
  const r = await budget.reserve(
    {
      taskId: f.execution.taskId,
      requestId,
      limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
    },
    new AbortController().signal,
  );
  assert.ok(r);
  await ledger.beginDispatch(requestId, r.reservationId, new AbortController().signal);
  await f.tasks.cancel(f.header, f.execution.taskId);
  f.database.sqlite.exec(
    "CREATE TRIGGER fail_settlement BEFORE UPDATE OF charged_micros ON agent_budget_reservations BEGIN SELECT RAISE(ABORT, '测试故障'); END",
  );
  await assert.rejects(
    createUsageReceipts({ db: f.database.db, ownerId: 'owner', execution: ledger.binding }).record(
      r.reservationId,
      { inputTokens: 100, outputTokens: 40 },
    ),
  );
  f.database.sqlite.exec('DROP TRIGGER fail_settlement');
  f.database.close();
  const reopened = openBudgetDatabase(path, f.now);
  t.after(reopened.close);
  t.after(() => {
    unlinkSync(path);
    rmdirSync(directory);
  });
  const runRecovery = recover;
  assert.equal((await runRecovery({ db: reopened.db, now: f.now })).receiptsApplied, 1);
  assert.equal((await runRecovery({ db: reopened.db, now: f.now })).receiptsApplied, 0);
  assert.equal(
    reopened.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
  const saved = await createCloudTasks({ db: reopened.db, now: f.now }).get(
    f.header,
    f.execution.taskId,
  );
  assert.equal(saved.result, null);
  assert.equal(saved.status, 'cancelled');
});
