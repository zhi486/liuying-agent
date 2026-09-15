import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createD1Budget } from '../src/budget.ts';
import { fixture, rate, start } from './execution-fixture.ts';

import * as loaded from '../src/execution-ledger.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { input } from './execution-fixture.ts';

test('错误执行权或不同 owner 不能借用已认领任务预留额度', async (t) => {
  const f = await fixture(t);
  await assert.rejects(
    loaded.createExecutionLedger({
      db: f.database.db,
      execution: { ...f.execution, token: '0'.repeat(64) },
      now: f.now,
    }),
  );
  const ledger = await loaded.createExecutionLedger({
    db: f.database.db,
    execution: f.execution,
    now: f.now,
  });
  f.database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('other',1800000,0)");
  for (const options of [
    { ownerId: 'other', execution: ledger.binding },
    { ownerId: 'owner', execution: { ...ledger.binding, executionHash: '0'.repeat(64) } },
  ]) {
    const budget = createD1Budget({ db: f.database.db, rate, now: f.now, ...options });
    assert.equal(
      await budget.reserve(
        {
          taskId: f.execution.taskId,
          requestId: `${f.execution.taskId}:1`,
          limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
        },
        new AbortController().signal,
      ),
      null,
    );
  }
  assert.equal(
    f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_budget_reservations').get()!.n,
    0,
  );
});

test('执行检查回包跨过手机设备凭据到期时刻，不能授权工具继续读取', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const expires = Number(
    f.database.sqlite
      .prepare('SELECT expires_at FROM agent_devices WHERE id = ?')
      .get(f.device.deviceId)!.expires_at,
  );
  f.setTime(expires - 1000);
  const accepted = await f.tasks.accept(f.header, input());
  const execution = await f.tasks.claim(f.header, accepted.task.id);
  let delay = false;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (delay && phase === 'after' && sql.includes('AS active')) f.setTime(expires + 1);
  });
  const ledger = await loaded.createExecutionLedger({ db, execution, now: f.now });
  delay = true;
  f.setTime(expires - 1);
  await assert.rejects(ledger.check(), /cancelled/);
});

test('派发许可回包期间费率到期也不能进入 fetch', async (t) => {
  const f = await fixture(t);
  const shortRate = { ...rate, expiresAtMillis: start + 1000 };
  let delay = false;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (delay && phase === 'after' && sql.includes("SET dispatch_state = 'may_have_dispatched'"))
      f.setTime(start + 1001);
  });
  const ledger = await loaded.createExecutionLedger({ db, execution: f.execution, now: f.now });
  const budget = createD1Budget({
    db,
    ownerId: 'owner',
    rate: shortRate,
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
  delay = true;
  f.setTime(start + 999);
  await assert.rejects(
    ledger.beginDispatch(requestId, r.reservationId, new AbortController().signal),
  );
  await budget.settle(r.reservationId, { kind: 'not_dispatched' });
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'uncertain',
  );
});

test('预留 SQL 排队跨月，不能借上月剩余额度发放新月请求', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const month = Date.UTC(2026, 9, 1);
  const monthRate = { ...rate, checkedAtMillis: month - 1000, expiresAtMillis: month + 60000 };
  f.database.sqlite.exec('UPDATE agent_budget_accounts SET monthly_limit_micros = 6720');
  f.setTime(month + 1);
  const old = createD1Budget({ db: f.database.db, ownerId: 'owner', rate: monthRate, now: f.now });
  assert.ok(
    await old.reserve(
      {
        taskId: 'other',
        requestId: 'other:1',
        limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
      },
      new AbortController().signal,
    ),
  );
  f.setTime(month - 1);
  const accepted = await f.tasks.accept(f.header, input());
  const execution = await f.tasks.claim(f.header, accepted.task.id);
  const ledger = await loaded.createExecutionLedger({ db: f.database.db, execution, now: f.now });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.includes('INSERT INTO agent_budget_reservations'))
      f.setTime(month + 1);
  });
  const budget = createD1Budget({
    db,
    ownerId: 'owner',
    rate: monthRate,
    now: f.now,
    execution: ledger.binding,
  });
  assert.equal(
    await budget.reserve(
      {
        taskId: execution.taskId,
        requestId: `${execution.taskId}:1`,
        limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
      },
      new AbortController().signal,
    ),
    null,
  );
  assert.equal(
    f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_budget_reservations').get()!.n,
    1,
  );
});
test('执行绑定预算只能派发一次，已派发不能声称未发送退款', async (t) => {
  const f = await fixture(t);
  const ledger = await loaded.createExecutionLedger({
    db: f.database.db,
    execution: f.execution,
    now: f.now,
  });
  const budget = createD1Budget({
    db: f.database.db,
    ownerId: ledger.ownerId,
    rate,
    now: f.now,
    ...{ execution: ledger.binding },
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
  const outcomes = await Promise.allSettled(
    [1, 2].map(() =>
      ledger.beginDispatch(requestId, reservation.reservationId, new AbortController().signal),
    ),
  );
  assert.equal(outcomes.filter((r) => r.status === 'fulfilled').length, 1);
  await budget.settle(reservation.reservationId, { kind: 'not_dispatched' });
  const row = f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
  assert.equal(row.state, 'uncertain');
  assert.equal(row.dispatch_state, 'may_have_dispatched');
  assert.equal(row.reserved_micros, 6720);
});

test('预检失败原子封闭 prepared，后来的派发不能再通过', async (t) => {
  const f = await fixture(t);
  const ledger = await loaded.createExecutionLedger({
    db: f.database.db,
    execution: f.execution,
    now: f.now,
  });
  const budget = createD1Budget({
    db: f.database.db,
    ownerId: ledger.ownerId,
    rate,
    now: f.now,
    ...{ execution: ledger.binding },
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
  await budget.settle(reservation.reservationId, { kind: 'uncertain' });
  await assert.rejects(
    ledger.beginDispatch(requestId, reservation.reservationId, new AbortController().signal),
  );
  const row = f.database.sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
  assert.equal(row.state, 'released');
  assert.equal(row.dispatch_state, 'closed_not_dispatched');
});

for (const cause of ['cancel', 'revoke', 'deadline', 'rate'] as const) {
  test(`${cause} 在计数期间发生后禁止实际派发`, async (t) => {
    const f = await fixture(t);
    const ledger = await loaded.createExecutionLedger({
      db: f.database.db,
      execution: f.execution,
      now: f.now,
    });
    const budget = createD1Budget({
      db: f.database.db,
      ownerId: ledger.ownerId,
      rate: { ...rate, expiresAtMillis: start + 5000 },
      now: f.now,
      ...{ execution: ledger.binding },
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
    if (cause === 'cancel') await f.tasks.cancel(f.header, f.execution.taskId);
    if (cause === 'revoke') await f.admin.revoke('owner', f.device.deviceId);
    if (cause === 'deadline') f.setTime(start + 30000);
    if (cause === 'rate') f.setTime(start + 5000);
    await assert.rejects(
      ledger.beginDispatch(requestId, reservation.reservationId, new AbortController().signal),
    );
    assert.equal(
      await budget.reserve(
        {
          taskId: f.execution.taskId,
          requestId: `${f.execution.taskId}:2`,
          limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
        },
        new AbortController().signal,
      ),
      null,
    );
  });
}
