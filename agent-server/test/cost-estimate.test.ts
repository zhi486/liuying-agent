import assert from 'node:assert/strict';
import { test } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createD1Budget } from '../src/budget.ts';
import { parseCostEstimate, taskCostEstimate } from '../src/cost-estimate.ts';
import { REFERENCE_RATE } from '../src/runtime-config.ts';
import { parseResult } from '../src/cloud-task-contracts.ts';

test('真实用量汇总、旧费率及参考价明确标记，未知用量不显示零', async (t) => {
  const database = openBudgetDatabase(); t.after(database.close);
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',1,1)");
  const rate = { ...REFERENCE_RATE, version: 'configured' };
  const now = rate.expiresAtMillis + 1;
  const budget = createD1Budget({ db: database.db, ownerId: 'owner', rate, now: () => now });
  const reserve = (step: number) => budget.reserve({ taskId: 'task', requestId: `task:${step}`,
    limits: { maxInputTokens: 20000, maxOutputTokens: 600 } }, new AbortController().signal);
  const first = await reserve(1); assert.ok(first);
  await budget.settle(first.reservationId, { kind: 'usage', usage: { inputTokens: 100, outputTokens: 40 } });
  const cost = await taskCostEstimate(database.db, 'owner', 'task', rate, now);
  assert.deepEqual(cost, { currency: 'USD', estimatedMicros: 78, inputTokens: 100, outputTokens: 40,
    usageComplete: true, rateStatus: 'stale' });
  assert.deepEqual(parseResult({ status: 'completed', answer: '回答', actions: [], cost }).cost, cost);
  assert.equal((await taskCostEstimate(database.db, 'owner', 'task', REFERENCE_RATE, now)).rateStatus, 'reference');
  assert.equal((await taskCostEstimate(database.db, 'other', 'task', rate, now)).estimatedMicros, 0);
  const second = await reserve(2); assert.ok(second);
  await budget.settle(second.reservationId, { kind: 'uncertain' });
  const uncertain = await taskCostEstimate(database.db, 'owner', 'task', rate, now);
  assert.equal(uncertain.estimatedMicros, null);
  assert.equal(uncertain.usageComplete, false);
  assert.equal(uncertain.inputTokens, 100);
  await budget.settle(second.reservationId, { kind: 'usage', usage: { inputTokens: 10, outputTokens: 0 } });
  assert.equal((await taskCostEstimate(database.db, 'owner', 'task', rate, now)).estimatedMicros, 81);
  assert.equal(uncertain.estimatedMicros, null); // 原回执保持快照，不伪称包含迟到费用。
});

test('费用读取故障只是待确认，不令已生成回答失败', async () => {
  const database = openBudgetDatabase(); database.close();
  const cost = await taskCostEstimate(database.db, 'owner', 'task', REFERENCE_RATE, Date.now());
  assert.equal(cost.estimatedMicros, null); assert.equal(cost.usageComplete, false);
});

test('费用契约兼容旧结果且拒绝负值、伪币种和未知用量零费用', () => {
  const valid = { currency: 'USD', estimatedMicros: 0, inputTokens: 0, outputTokens: 0,
    usageComplete: true, rateStatus: 'current' };
  parseCostEstimate(valid);
  assert.equal(parseResult({ status: 'completed', answer: '旧回答', actions: [] }).cost, undefined);
  for (const bad of [{ ...valid, currency: 'CNY' }, { ...valid, estimatedMicros: -1 },
    { ...valid, usageComplete: false }, { ...valid, estimatedMicros: null },
    { ...valid, inputTokens: Number.MAX_SAFE_INTEGER + 1 }, { ...valid, extra: true }]) {
    assert.throws(() => parseCostEstimate(bad));
    assert.throws(() => parseResult({ status: 'failed', reason: 'model_unavailable', actions: [], cost: bad }));
  }
});
