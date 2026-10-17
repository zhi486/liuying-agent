import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createD1Budget } from '../src/budget.ts';
import { createExecutionLedger } from '../src/execution-ledger.ts';
import { fixture, rate } from './execution-fixture.ts';

import * as loaded from '../src/usage-receipts.ts';
const usage = { inputTokens: 100, outputTokens: 40 };
test('收据在 SQL 层必须绑定非空预留 ID', async (t) => {
  const f = await fixture(t);
  assert.throws(() =>
    f.database.sqlite.exec(
      'INSERT INTO agent_usage_receipts(reservation_id,input_tokens,output_tokens,received_at_millis) VALUES (NULL,1,1,0)',
    ),
  );
});
async function setup(t: Parameters<typeof fixture>[0]) {
  const f = await fixture(t);
  const ledger = await createExecutionLedger({
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
  const reservation = await budget.reserve(
    {
      taskId: f.execution.taskId,
      requestId,
      limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
    },
    new AbortController().signal,
  );
  assert.ok(reservation);
  await ledger.beginDispatch(requestId, reservation.reservationId, new AbortController().signal);
  return { ...f, ledger, budget, id: reservation.reservationId };
}

test('取消后相同有效收据只结算一次，迟到未知通知不覆盖 78 微美元', async (t) => {
  const f = await setup(t);
  const receipts = loaded.createUsageReceipts({
    db: f.database.db,
    ownerId: 'owner',
    execution: f.ledger.binding,
  });
  await f.tasks.cancel(f.header, f.execution.taskId);
  await Promise.all([receipts.record(f.id, usage), receipts.record(f.id, usage)]);
  await f.budget.settle(f.id, { kind: 'uncertain' });
  const row = f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
  assert.equal(row.state, 'settled');
  assert.equal(row.charged_micros, 78);
  assert.equal(
    f.database.sqlite.prepare('SELECT applied FROM agent_usage_receipts').get()!.applied,
    1,
  );
});

test('不同用量收据保留首份并标记账本异常，不关闭账户', async (t) => {
  const f = await setup(t);
  const receipts = loaded.createUsageReceipts({
    db: f.database.db,
    ownerId: 'owner',
    execution: f.ledger.binding,
  });
  await receipts.record(f.id, usage);
  await assert.rejects(receipts.record(f.id, { inputTokens: 200, outputTokens: 40 }));
  assert.equal(
    f.database.sqlite.prepare('SELECT input_tokens FROM agent_usage_receipts').get()!.input_tokens,
    100,
  );
  assert.equal(
    f.database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted,
    0,
  );
  assert.equal(f.database.sqlite.prepare('SELECT violation FROM agent_budget_reservations').get()!.violation, 1);
});

test('结算事务失败仍留收据，重试只记账；错误执行与非法用量不产生证据', async (t) => {
  const f = await setup(t);
  f.database.sqlite.exec(
    "CREATE TRIGGER fail_settlement BEFORE UPDATE OF charged_micros ON agent_budget_reservations BEGIN SELECT RAISE(ABORT, '测试结算故障'); END",
  );
  const receipts = loaded.createUsageReceipts({
    db: f.database.db,
    ownerId: 'owner',
    execution: f.ledger.binding,
  });
  await assert.rejects(receipts.record(f.id, usage));
  assert.equal(
    f.database.sqlite.prepare('SELECT applied FROM agent_usage_receipts').get()!.applied,
    0,
  );
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'reserved',
  );
  f.database.sqlite.exec('DROP TRIGGER fail_settlement');
  await loaded.applyUsageReceipt({ db: f.database.db, ownerId: 'owner', reservationId: f.id });
  assert.equal(
    f.database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
  await assert.rejects(
    loaded
      .createUsageReceipts({
        db: f.database.db,
        ownerId: 'owner',
        execution: { ...f.ledger.binding, executionHash: '0'.repeat(64) },
      })
      .record(f.id, usage),
  );
  await assert.rejects(receipts.record(f.id, { inputTokens: -1, outputTokens: 40 }));
});
