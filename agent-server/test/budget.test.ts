import assert from 'node:assert/strict';
import { test } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createD1Budget } from '../src/budget.ts';
import type { RateCard } from '../src/budget.ts';
import { mkdtempSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const timestamp = Date.UTC(2026, 8, 14, 10);
const rate: RateCard = {
  version: 'fixture_peak',
  model: 'deepseek-flash',
  inputMicrosPerMillion: 300000,
  outputMicrosPerMillion: 1200000,
  checkedAtMillis: timestamp,
  expiresAtMillis: timestamp + 86400000,
};
const limits = { maxInputTokens: 20000, maxOutputTokens: 600 };
const reserve = (budget: ReturnType<typeof createD1Budget>, taskId = 'task_1') =>
  budget.reserve({ taskId, requestId: `${taskId}:1`, limits }, new AbortController().signal);
const usage = { kind: 'usage', usage: { inputTokens: 100, outputTokens: 40 } } as const;
const account = (
  database: ReturnType<typeof openBudgetDatabase>,
  ownerId = 'owner_1',
  limit = 13440,
) =>
  database.sqlite
    .prepare('INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, ?)')
    .run(ownerId, limit);
const port = (database: ReturnType<typeof openBudgetDatabase>, ownerId = 'owner_1', card = rate) =>
  createD1Budget({ db: database.db, ownerId, rate: card, now: () => timestamp });
const row = (database: ReturnType<typeof openBudgetDatabase>, id: string) =>
  database.sqlite.prepare('SELECT * FROM agent_budget_reservations WHERE id = ?').get(id)!;

test('持久预算端口不会为没有预置账户的请求发放额度', async () => {
  const database = openBudgetDatabase();
  try {
    const result = await reserve(port(database));
    assert.equal(result, null);
  } finally {
    database.close();
  }
});

test('三个并发请求超出参考预算仍登记，重复请求不能增加预留', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const granted = await Promise.all(
    ['task_1', 'task_2', 'task_3'].map((id) => reserve(budget, id)),
  );
  assert.equal(granted.filter(Boolean).length, 3);
  assert.equal(row(database, granted[0]!.reservationId).reserved_micros, 6720);
  assert.equal(await reserve(budget), null);
  assert.equal(
    database.sqlite.prepare('SELECT COUNT(*) AS count FROM agent_budget_reservations').get()!.count,
    3,
  );
});

test('按实际用量结算幂等，冲突用量不覆盖已经入账的费用', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await budget.settle(reserved.reservationId, usage);
  await budget.settle(reserved.reservationId, usage);
  assert.equal(row(database, reserved.reservationId).charged_micros, 78);
  await assert.rejects(
    budget.settle(reserved.reservationId, {
      kind: 'usage',
      usage: { inputTokens: 101, outputTokens: 40 },
    }),
    /budget_unavailable/,
  );
  await budget.settle(reserved.reservationId, { kind: 'uncertain' });
  await budget.settle(reserved.reservationId, { kind: 'not_dispatched' });
  assert.equal(row(database, reserved.reservationId).state, 'settled');
  assert.equal(row(database, reserved.reservationId).charged_micros, 78);
  assert.ok(await reserve(budget, 'task_2'));
});

test('未知费用保持待确认但不封禁新任务，请求键不可复用', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database, 'owner_1', 6720);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await budget.settle(reserved.reservationId, { kind: 'uncertain' });
  await budget.settle(reserved.reservationId, { kind: 'not_dispatched' });
  assert.equal(row(database, reserved.reservationId).state, 'uncertain');
  assert.ok(await reserve(budget, 'task_2'));
  await budget.settle(reserved.reservationId, usage);
  assert.equal(row(database, reserved.reservationId).state, 'settled');

  account(database, 'owner_2', 6720);
  const other = port(database, 'owner_2');
  const unsent = await reserve(other);
  assert.ok(unsent);
  await other.settle(unsent.reservationId, { kind: 'not_dispatched' });
  await other.settle(unsent.reservationId, { kind: 'uncertain' });
  assert.equal(row(database, unsent.reservationId).state, 'released');
  assert.equal(await reserve(other), null);
  assert.ok(await reserve(other, 'task_2'));
});

test('所有者隔离：相同请求键互不冲突，不能结算其他人的预留', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  account(database, 'owner_2');
  const first = port(database);
  const second = port(database, 'owner_2');
  const one = await reserve(first);
  const two = await reserve(second);
  assert.ok(one);
  assert.ok(two);
  await assert.rejects(second.settle(one.reservationId, usage), /budget_unavailable/);
  assert.equal(row(database, one.reservationId).state, 'reserved');
  assert.equal(row(database, two.reservationId).state, 'reserved');
});

test('费率过期仍记账但非法请求上限拒绝，历史按原快照结算', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  for (const expired of [
    { ...rate, checkedAtMillis: timestamp - 86400000, expiresAtMillis: timestamp },
    { ...rate, checkedAtMillis: timestamp + 1 },
  ])
    assert.ok(await reserve(port(database, 'owner_1', expired), `expired_${expired.checkedAtMillis}`));
  const budget = port(database);
  for (const invalid of [
    { maxInputTokens: 20001, maxOutputTokens: 600 },
    { maxInputTokens: 20000, maxOutputTokens: 1601 },
    { maxInputTokens: 0, maxOutputTokens: 600 },
  ]) {
    assert.equal(
      await budget.reserve(
        { taskId: 'task_1', requestId: 'task_1:1', limits: invalid },
        new AbortController().signal,
      ),
      null,
    );
  }
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await port(database, 'owner_1', { ...rate, inputMicrosPerMillion: 600000 }).settle(
    reserved.reservationId,
    usage,
  );
  assert.equal(row(database, reserved.reservationId).charged_micros, 78);
  for (const invalid of [
    { ...rate, inputMicrosPerMillion: 0 },
    { ...rate, outputMicrosPerMillion: 1.5 },
    { ...rate, expiresAtMillis: timestamp + 86400001 },
  ]) {
    assert.throws(() => port(database, 'owner_1', invalid), /budget_unavailable/);
  }
  assert.throws(() => account(database, 'over_limit', 1800001));
});

test('1600 token 输出上限登记完整预留，按供应商真实用量结算', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await budget.reserve({ taskId: 'task_1', requestId: 'task_1:1',
    limits: { maxInputTokens: 20000, maxOutputTokens: 1600 },
  }, new AbortController().signal);
  assert.ok(reserved);
  assert.equal(row(database, reserved.reservationId).reserved_micros, 7920);
  await budget.settle(reserved.reservationId, { kind: 'usage',
    usage: { inputTokens: 100, outputTokens: 1600 },
  });
  assert.equal(row(database, reserved.reservationId).charged_micros, 1950);
});

test('估算超出预留时记录全部费用及异常，不关闭后续任务', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await budget.settle(reserved.reservationId, {
    kind: 'usage',
    usage: { inputTokens: 30000, outputTokens: 0 },
  });
  assert.equal(row(database, reserved.reservationId).charged_micros, 9000);
  assert.equal(row(database, reserved.reservationId).violation, 1);
  assert.equal(
    database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted,
    0,
  );
  assert.ok(await reserve(port(database), 'task_2'));
  database.sqlite.exec('UPDATE agent_budget_accounts SET halted = 1');
  assert.ok(await reserve(port(database), 'task_3'));
});

test('释放后收到用量仍记账并标记异常，不封禁账户', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await budget.settle(reserved.reservationId, { kind: 'not_dispatched' });
  await budget.settle(reserved.reservationId, usage);
  assert.equal(row(database, reserved.reservationId).charged_micros, 78);
  assert.equal(row(database, reserved.reservationId).violation, 1);
  assert.ok(await reserve(budget, 'task_2'));
});

test('结算写入失败时原记录保留，不伪造已知费用', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  database.sqlite.exec(`CREATE TRIGGER reject_settlement BEFORE UPDATE ON agent_budget_reservations
    BEGIN SELECT RAISE(ABORT, '测试事务回滚'); END;`);
  await assert.rejects(
    budget.settle(reserved.reservationId, {
      kind: 'usage',
      usage: { inputTokens: 30000, outputTokens: 0 },
    }),
    /budget_unavailable/,
  );
  assert.equal(row(database, reserved.reservationId).state, 'reserved');
  assert.equal(row(database, reserved.reservationId).charged_micros, null);
  assert.equal(
    database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted,
    0,
  );
});

test('预取消不发放额度，数据库错误不降级成内存账本', async (t) => {
  const database = openBudgetDatabase();
  account(database);
  const budget = port(database);
  const abort = new AbortController();
  abort.abort();
  assert.equal(
    await budget.reserve({ taskId: 'task_1', requestId: 'task_1:1', limits }, abort.signal),
    null,
  );
  const reserved = await reserve(budget);
  assert.ok(reserved);
  await assert.rejects(
    budget.settle(reserved.reservationId, {
      kind: 'usage',
      usage: { inputTokens: -1, outputTokens: 0 },
    }),
    /budget_unavailable/,
  );
  assert.equal(row(database, reserved.reservationId).state, 'reserved');
  database.close();
  await assert.rejects(reserve(budget, 'task_2'), /budget_unavailable/);
});

test('文件库重开仍保存未知预留，UTC 跨月不修改旧账本', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'liuying-budget-test-'));
  const path = join(directory, 'budget.sqlite');
  let database = openBudgetDatabase(path);
  try {
    account(database, 'owner_1', 6720);
    const budget = port(database);
    const reserved = await reserve(budget);
    assert.ok(reserved);
    await budget.settle(reserved.reservationId, { kind: 'uncertain' });
    database.close();
    database = openBudgetDatabase(path);
    assert.ok(await reserve(port(database), 'task_extra'));
    const nextMonth = Date.UTC(2026, 9, 1);
    const next = createD1Budget({
      db: database.db,
      ownerId: 'owner_1',
      rate: { ...rate, checkedAtMillis: nextMonth, expiresAtMillis: nextMonth + 86400000 },
      now: () => nextMonth,
    });
    const fresh = await reserve(next, 'task_2');
    assert.ok(fresh);
    assert.equal(row(database, fresh.reservationId).period, '2026-10');
    assert.equal(row(database, reserved.reservationId).period, '2026-09');
    assert.equal(row(database, reserved.reservationId).state, 'uncertain');
  } finally {
    database.close();
    // 仅移除本测试刚创建的单个数据库和空目录，不递归、不触碰已有用户文件。
    unlinkSync(path);
    rmdirSync(directory);
  }
});

test('同键并发只发一笔，构造后的费率修改不能影响已有端口', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const mutableRate = { ...rate };
  const budget = port(database, 'owner_1', mutableRate);
  mutableRate.inputMicrosPerMillion = 1;
  const result = await Promise.all([reserve(budget), reserve(budget), reserve(budget)]);
  assert.equal(result.filter(Boolean).length, 1);
  assert.equal(row(database, result.find(Boolean)!.reservationId).reserved_micros, 6720);
});

test('整数微美元向上取整；相同与冲突结算并发不会双重扣费', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  account(database);
  const budget = port(database);
  const reserved = await reserve(budget);
  assert.ok(reserved);
  const small = { kind: 'usage', usage: { inputTokens: 1, outputTokens: 0 } } as const;
  await Promise.all([
    budget.settle(reserved.reservationId, small),
    budget.settle(reserved.reservationId, small),
  ]);
  assert.equal(row(database, reserved.reservationId).charged_micros, 1);
  const second = await reserve(budget, 'task_2');
  assert.ok(second);
  const results = await Promise.allSettled([
    budget.settle(second.reservationId, small),
    budget.settle(second.reservationId, usage),
  ]);
  assert.equal(results.filter((result) => result.status === 'fulfilled').length, 1);
  assert.equal(row(database, second.reservationId).charged_micros, 1);
});

test('真实用量与释放或未知通知并发，任何顺序都不能清空已知费用', async (t) => {
  for (const kind of ['uncertain', 'not_dispatched'] as const) {
    for (const usageFirst of [true, false]) {
      const database = openBudgetDatabase();
      t.after(database.close);
      account(database);
      const budget = port(database);
      const reserved = await reserve(budget);
      assert.ok(reserved);
      const settlements = usageFirst ? [usage, { kind }] : [{ kind }, usage];
      await Promise.all(
        settlements.map((settlement) => budget.settle(reserved.reservationId, settlement)),
      );
      const settled = row(database, reserved.reservationId);
      assert.equal(settled.state, 'settled');
      assert.equal(settled.charged_micros, 78);
      assert.equal(settled.violation, kind === 'not_dispatched' && !usageFirst ? 1 : 0);
    }
  }
});
