import { AgentError } from './contracts.ts';
import type { BudgetPort } from './contracts.ts';
import type { BudgetDatabase } from './d1.ts';
import {
  EXECUTION_ACTIVE_SQL,
  EXECUTION_CLOCK_SQL,
  type ExecutionBinding,
} from './execution-ledger.ts';

export type RateCard = Readonly<{
  version: string;
  model: 'deepseek-flash';
  inputMicrosPerMillion: number;
  outputMicrosPerMillion: number;
  checkedAtMillis: number;
  expiresAtMillis: number;
}>;

const unavailable = () => new AgentError('budget_unavailable');
const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const identifier = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(value);

function charge(input: number, output: number, inputRate: number, outputRate: number): number {
  const amount =
    (BigInt(input) * BigInt(inputRate) + BigInt(output) * BigInt(outputRate) + 999999n) / 1000000n;
  if (amount > BigInt(Number.MAX_SAFE_INTEGER)) throw unavailable();
  return Number(amount);
}

export function createD1Budget(options: {
  db: BudgetDatabase;
  ownerId: string;
  rate: RateCard;
  now?: () => number;
  newId?: () => string;
  execution?: ExecutionBinding;
}): BudgetPort {
  const { db, ownerId } = options;
  const rate = { ...options.rate };
  const now = options.now ?? Date.now;
  const newId = options.newId ?? (() => crypto.randomUUID());
  const execution = options.execution ? { ...options.execution } : null;
  if (
    !identifier(ownerId) ||
    !identifier(rate.version) ||
    rate.model !== 'deepseek-flash' ||
    !integer(rate.inputMicrosPerMillion) ||
    rate.inputMicrosPerMillion < 1 ||
    !integer(rate.outputMicrosPerMillion) ||
    rate.outputMicrosPerMillion < 1 ||
    !integer(rate.checkedAtMillis) ||
    !integer(rate.expiresAtMillis) ||
    rate.expiresAtMillis <= rate.checkedAtMillis ||
    rate.expiresAtMillis - rate.checkedAtMillis > 86400000
  )
    throw unavailable();
  return {
    async reserve(request, signal) {
      try {
        const timestamp = now();
        const { maxInputTokens, maxOutputTokens } = request.limits;
        if (
          signal.aborted ||
          !integer(timestamp) ||
          timestamp < rate.checkedAtMillis ||
          timestamp >= rate.expiresAtMillis ||
          !integer(maxInputTokens) ||
          maxInputTokens < 1 ||
          maxInputTokens > 20000 ||
          !integer(maxOutputTokens) ||
          maxOutputTokens < 1 ||
          maxOutputTokens > 600 ||
          !identifier(request.taskId) ||
          (execution !== null && request.taskId !== execution.taskId) ||
          ![1, 2, 3].some((step) => request.requestId === `${request.taskId}:${step}`)
        )
          return null;
        const period = new Date(timestamp).toISOString().slice(0, 7);
        const id = newId();
        if (!identifier(id)) throw unavailable();
        const reserved = charge(
          maxInputTokens,
          maxOutputTokens,
          rate.inputMicrosPerMillion,
          rate.outputMicrosPerMillion,
        );
        // 一条 SQL 完成查余量和插入，不能拆成并发下会超发的“先读再写”。
        const periodSql = execution ? "substr(datetime(clock.time / 1000, 'unixepoch'),1,7)" : '?';
        const result = await db
          .prepare(
            `
          ${execution ? EXECUTION_CLOCK_SQL : ''}
          INSERT INTO agent_budget_reservations
            (id, owner_id, task_id, request_id, period, model, rate_version,
             input_rate, output_rate, reserved_micros, state, created_at_millis,
             execution_hash, dispatch_state, rate_expires_at_millis)
          SELECT ?, a.owner_id, ?, ?, ${periodSql}, ?, ?, ?, ?, ?, 'reserved', ${execution ? 'clock.time' : '?'}, ?, ?, ?
          FROM agent_budget_accounts a ${execution ? ', clock' : ''}
          WHERE a.owner_id = ? AND a.halted = 0
            ${
              execution
                ? `AND clock.time >= ? AND clock.time < ? AND EXISTS (
              SELECT 1 FROM agent_cloud_tasks t WHERE t.id = ? AND t.owner_id = a.owner_id
                AND t.execution_hash = ? AND ${EXECUTION_ACTIVE_SQL})`
                : ''
            }
            AND NOT EXISTS (SELECT 1 FROM agent_budget_reservations r WHERE r.owner_id = a.owner_id AND r.request_id = ?)
            AND ? + COALESCE((SELECT SUM(CASE r.state
              WHEN 'settled' THEN r.charged_micros WHEN 'released' THEN 0 ELSE r.reserved_micros END)
              FROM agent_budget_reservations r WHERE r.owner_id = a.owner_id AND r.period = ${periodSql}), 0) <= a.monthly_limit_micros
        `,
          )
          .bind(
            ...(execution ? [timestamp] : []),
            id,
            request.taskId,
            request.requestId,
            ...(execution ? [] : [period]),
            rate.model,
            rate.version,
            rate.inputMicrosPerMillion,
            rate.outputMicrosPerMillion,
            reserved,
            ...(execution ? [] : [timestamp]),
            execution?.executionHash ?? null,
            execution ? 'prepared' : null,
            execution ? rate.expiresAtMillis : null,
            ownerId,
            ...(execution
              ? [
                  rate.checkedAtMillis,
                  rate.expiresAtMillis,
                  execution.taskId,
                  execution.executionHash,
                ]
              : []),
            request.requestId,
            reserved,
            ...(execution ? [] : [period]),
          )
          .run();
        // 取消期间 SQL 可能已经落库：返回预留，交给核心的迟到释放机制处理。
        return result.meta.changes === 1 ? { reservationId: id } : null;
      } catch {
        throw unavailable();
      }
    },
    settle: createBudgetSettlement({ db, ownerId }),
  };
}

// 独立收据与维护仅复用结算，不需要新费率或仍有效的设备凭据。
export function createBudgetSettlement({
  db,
  ownerId,
}: {
  db: BudgetDatabase;
  ownerId: string;
}): BudgetPort['settle'] {
  const select = (id: string) =>
    db
      .prepare('SELECT * FROM agent_budget_reservations WHERE id = ? AND owner_id = ?')
      .bind(id, ownerId);
  return async (id, settlement) => {
    try {
      if (!identifier(id)) throw unavailable();
      const previous = (await select(id).all()).results[0];
      if (!previous) throw unavailable();
      if (settlement.kind !== 'usage') {
        if (settlement.kind !== 'uncertain' && settlement.kind !== 'not_dispatched')
          throw unavailable();
        const state = settlement.kind === 'uncertain' ? 'uncertain' : 'released';
        await db
          .prepare(
            `UPDATE agent_budget_reservations SET
                state = CASE WHEN execution_hash IS NULL THEN ?
                  WHEN dispatch_state = 'prepared' THEN 'released' ELSE 'uncertain' END,
                dispatch_state = CASE WHEN execution_hash IS NOT NULL AND dispatch_state = 'prepared'
                  THEN 'closed_not_dispatched' ELSE dispatch_state END
            WHERE id = ? AND owner_id = ? AND state = 'reserved'`,
          )
          .bind(state, id, ownerId)
          .run();
        return;
      }
      const { inputTokens, outputTokens } = settlement.usage;
      const inputRate = previous.input_rate;
      const outputRate = previous.output_rate;
      if (
        !integer(inputTokens) ||
        !integer(outputTokens) ||
        !integer(inputRate) ||
        !integer(outputRate)
      )
        throw unavailable();
      const charged = charge(inputTokens, outputTokens, inputRate, outputRate);
      // D1 batch 为事务：真实费用和熔断必须一起提交，失败时保留原来的额度。
      const results = await db.batch([
        db
          .prepare(
            `UPDATE agent_budget_reservations
            SET state = 'settled', charged_micros = ?, input_tokens = ?, output_tokens = ?,
              violation = CASE WHEN ? > reserved_micros OR (state = 'released' AND ? > 0) THEN 1 ELSE violation END
            WHERE id = ? AND owner_id = ? AND state <> 'settled'
          `,
          )
          .bind(charged, inputTokens, outputTokens, charged, charged, id, ownerId),
        db
          .prepare(
            `UPDATE agent_budget_accounts SET halted = 1 WHERE owner_id = ?
            AND EXISTS (SELECT 1 FROM agent_budget_reservations WHERE id = ? AND owner_id = ? AND violation = 1)
          `,
          )
          .bind(ownerId, id, ownerId),
        select(id),
      ]);
      const settled = results[2]?.results[0];
      if (
        !settled ||
        settled.state !== 'settled' ||
        settled.input_tokens !== inputTokens ||
        settled.output_tokens !== outputTokens ||
        settled.charged_micros !== charged
      )
        throw unavailable();
    } catch {
      throw unavailable();
    }
  };
}
