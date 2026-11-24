import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fixture, input, rate } from './execution-fixture.ts';
import { createD1Budget } from '../src/budget.ts';
import { createUsageReceipts } from '../src/usage-receipts.ts';
import * as ledger from '../src/execution-ledger.ts';
import type { BudgetDatabase, BudgetStatement } from '../src/d1.ts';

test('Free 满页只恢复五条收据且最多 36 条真实 SQL，下一次调用继续游标不重复收费', async (t) => {
  const f = await fixture(t);
  f.database.sqlite.exec(
    "CREATE TRIGGER fail_free_settle BEFORE UPDATE OF charged_micros ON agent_budget_reservations BEGIN SELECT RAISE(ABORT, '测试故障'); END",
  );
  for (let index = 0; index < 6; index++) {
    const execution =
      index === 0
        ? f.execution
        : await f.tasks.claim(f.header, (await f.tasks.accept(f.header, input())).task.id);
    const bound = await ledger.createExecutionLedger({
      db: f.database.db,
      execution,
      now: f.now,
    });
    const budget = createD1Budget({
      db: f.database.db,
      ownerId: 'owner',
      execution: bound.binding,
      rate,
      now: f.now,
    });
    const requestId = `${execution.taskId}:1`;
    const reserved = await budget.reserve(
      {
        taskId: execution.taskId,
        requestId,
        limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
      },
      new AbortController().signal,
    );
    assert.ok(reserved);
    await bound.beginDispatch(requestId, reserved.reservationId, new AbortController().signal);
    await assert.rejects(
      createUsageReceipts({
        db: f.database.db,
        ownerId: 'owner',
        execution: bound.binding,
      }).record(reserved.reservationId, { inputTokens: 100, outputTokens: 40 }),
    );
    await f.tasks.cancel(f.header, execution.taskId);
  }
  f.database.sqlite.exec('DROP TRIGGER fail_free_settle');

  let sqlCount = 0;
  const originals = new WeakMap<BudgetStatement, BudgetStatement>();
  const wrap = (original: BudgetStatement): BudgetStatement => {
    const result: BudgetStatement = {
      bind: (...values) => wrap(original.bind(...values)),
      all: () => {
        sqlCount++;
        return original.all();
      },
      run: () => {
        sqlCount++;
        return original.run();
      },
    };
    originals.set(result, original);
    return result;
  };
  const db: BudgetDatabase = {
    prepare: (sql) => wrap(f.database.db.prepare(sql)),
    batch: (statements) => {
      sqlCount += statements.length;
      return f.database.db.batch(statements.map((statement) => originals.get(statement)!));
    },
  };
  const first = await ledger.recoverFreeExecutions({
    db,
    now: f.now,
    ...{ limit: 100 },
  });
  assert.equal(first.processed, 5);
  assert.equal(first.receiptsApplied, 5);
  assert.equal(sqlCount, 36);
  assert.ok(first.nextCursor);
  assert.equal(
    f.database.sqlite
      .prepare('SELECT SUM(charged_micros) AS charged FROM agent_budget_reservations')
      .get()!.charged,
    390,
  );
  sqlCount = 0;
  const second = await ledger.recoverFreeExecutions({
    db,
    now: f.now,
    cursor: first.nextCursor,
  });
  assert.equal(second.processed, 1);
  assert.equal(second.receiptsApplied, 1);
  assert.equal(second.nextCursor, null);
  assert.equal(sqlCount, 8);
  assert.equal((await ledger.recoverFreeExecutions({ db, now: f.now })).processed, 0);
  assert.equal(
    f.database.sqlite
      .prepare('SELECT SUM(charged_micros) AS charged FROM agent_budget_reservations')
      .get()!.charged,
    468,
  );
});
