import type { BudgetDatabase } from './d1.ts';
import type { RateCard } from './budget.ts';

/** 回答完成时的估算快照；正式账本仍可在迟到用量到达后修正。 */
export type CostEstimate = Readonly<{
  currency: 'USD';
  estimatedMicros: number | null;
  inputTokens: number;
  outputTokens: number;
  usageComplete: boolean;
  rateStatus: 'current' | 'stale' | 'reference';
}>;

const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

export function parseCostEstimate(value: unknown): CostEstimate {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_cost');
  const row = value as Record<string, unknown>;
  if (Object.keys(row).sort().join(',') !== 'currency,estimatedMicros,inputTokens,outputTokens,rateStatus,usageComplete' ||
      row.currency !== 'USD' || !integer(row.inputTokens) || !integer(row.outputTokens) ||
      typeof row.usageComplete !== 'boolean' ||
      typeof row.rateStatus !== 'string' || !['current', 'stale', 'reference'].includes(row.rateStatus) ||
      (row.usageComplete ? !integer(row.estimatedMicros) : row.estimatedMicros !== null))
    throw new Error('invalid_cost');
  return Object.freeze({ ...row }) as CostEstimate;
}

export async function taskCostEstimate(
  db: BudgetDatabase, ownerId: string, taskId: string, rate: RateCard, now: number,
): Promise<CostEstimate> {
  const rateStatus = rate.version.startsWith('reference_') ? 'reference'
    : now < rate.checkedAtMillis || now >= rate.expiresAtMillis ? 'stale' : 'current';
  const unknown: CostEstimate = { currency: 'USD', estimatedMicros: null,
    inputTokens: 0, outputTokens: 0, usageComplete: false, rateStatus };
  try {
    const rows = (await db.prepare(`SELECT state, charged_micros, input_tokens, output_tokens
      FROM agent_budget_reservations WHERE owner_id = ? AND task_id = ? LIMIT 4`)
      .bind(ownerId, taskId).all()).results;
    if (rows.length > 3) return unknown;
    let estimatedMicros = 0, inputTokens = 0, outputTokens = 0, usageComplete = true;
    for (const row of rows) {
      if (row.state === 'released') continue;
      if (row.state !== 'settled') { usageComplete = false; continue; }
      if (!integer(row.charged_micros) || !integer(row.input_tokens) || !integer(row.output_tokens)) return unknown;
      estimatedMicros += row.charged_micros;
      inputTokens += row.input_tokens;
      outputTokens += row.output_tokens;
    }
    return parseCostEstimate({ currency: 'USD', estimatedMicros: usageComplete ? estimatedMicros : null,
      inputTokens, outputTokens, usageComplete, rateStatus });
  } catch {
    // 展示估算失败不丢弃已经生成的回答；不伪造零费用或重新请求模型。
    return unknown;
  }
}
