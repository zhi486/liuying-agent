import type { BudgetDatabase } from './d1.ts';
import { AgentError, type Usage } from './contracts.ts';
import { createBudgetSettlement } from './budget.ts';
import type { ExecutionBinding } from './execution-ledger.ts';
import { DATABASE_TIME_SQL } from './cloud-task-contracts.ts';

const unavailable = () => new AgentError('budget_unavailable');
const validTokens = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

// 可信维护只重放已经存在的证据，不接受客户端提供的用量。
export async function applyUsageReceipt({
  db,
  ownerId,
  reservationId,
}: {
  db: BudgetDatabase;
  ownerId: string;
  reservationId: string;
}) {
  try {
    const row = (
      await db
        .prepare(
          `SELECT u.* FROM agent_usage_receipts u
      JOIN agent_budget_reservations r ON r.id = u.reservation_id
      WHERE r.id = ? AND r.owner_id = ? AND r.execution_hash IS NOT NULL`,
        )
        .bind(reservationId, ownerId)
        .all()
    ).results[0];
    if (!row) throw unavailable();
    if (row.applied === 1) return;
    const usage = {
      inputTokens: Number(row.input_tokens),
      outputTokens: Number(row.output_tokens),
    };
    await createBudgetSettlement({ db, ownerId })(reservationId, { kind: 'usage', usage });
    await db
      .prepare('UPDATE agent_usage_receipts SET applied = 1 WHERE reservation_id = ?')
      .bind(reservationId)
      .run();
  } catch {
    throw unavailable();
  }
}

export function createUsageReceipts({
  db,
  ownerId,
  execution,
}: {
  db: BudgetDatabase;
  ownerId: string;
  execution: ExecutionBinding;
}) {
  const binding = { ...execution };
  const belongs = `r.id = ? AND r.owner_id = ? AND r.task_id = ? AND r.execution_hash = ?
    AND r.dispatch_state = 'may_have_dispatched'`;
  const values = (id: string) => [id, ownerId, binding.taskId, binding.executionHash] as const;
  return {
    async record(reservationId: string, input: Usage) {
      try {
        const { inputTokens, outputTokens } = input;
        if (!validTokens(inputTokens) || !validTokens(outputTokens)) throw unavailable();
        // 先独立提交收据；后续结算故障不能回滚已取得的供应商费用证据。
        const results = await db.batch([
          db
            .prepare(
              `INSERT INTO agent_usage_receipts
            (reservation_id, input_tokens, output_tokens, received_at_millis)
            SELECT r.id, ?, ?, ${DATABASE_TIME_SQL} FROM agent_budget_reservations r WHERE ${belongs}
            ON CONFLICT(reservation_id) DO NOTHING`,
            )
            .bind(inputTokens, outputTokens, ...values(reservationId)),
          db
            .prepare(
              `UPDATE agent_budget_reservations SET violation = 1 WHERE id = ? AND EXISTS (
            SELECT 1 FROM agent_usage_receipts u JOIN agent_budget_reservations r ON r.id = u.reservation_id
            WHERE ${belongs} AND (u.input_tokens <> ? OR u.output_tokens <> ?))`,
            )
            .bind(reservationId, ...values(reservationId), inputTokens, outputTokens),
          db
            .prepare(
              `SELECT u.* FROM agent_usage_receipts u JOIN agent_budget_reservations r ON r.id = u.reservation_id WHERE ${belongs}`,
            )
            .bind(...values(reservationId)),
        ]);
        const saved = results[2]?.results[0];
        if (!saved || saved.input_tokens !== inputTokens || saved.output_tokens !== outputTokens)
          throw unavailable();
        await applyUsageReceipt({ db, ownerId, reservationId });
      } catch {
        throw unavailable();
      }
    },
  };
}
