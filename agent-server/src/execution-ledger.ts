import type { BudgetDatabase } from './d1.ts';
import { AgentError } from './contracts.ts';
import { digest } from './device-auth.ts';
import { DATABASE_TIME_SQL, timestamp, uuid } from './cloud-task-contracts.ts';
import { applyUsageReceipt } from './usage-receipts.ts';

export type ExecutionBinding = Readonly<{
  taskId: string;
  executionHash: string;
}>;
// 使用查询内 clock.time，预留、派发和恢复复用同一原设备/截止条件。
export const EXECUTION_ACTIVE_SQL = `t.status = 'running' AND t.deadline_at > clock.time
  AND EXISTS (SELECT 1 FROM agent_devices d WHERE d.id = t.device_id AND d.owner_id = t.owner_id
    AND d.created_at <= clock.time AND d.expires_at > clock.time AND d.revoked_at IS NULL)`;
export const EXECUTION_CLOCK_SQL = `WITH clock AS (SELECT MAX(?, ${DATABASE_TIME_SQL}) AS time)`;

export async function createExecutionLedger({
  db,
  execution,
  now = Date.now,
}: {
  db: BudgetDatabase;
  execution: { taskId: string; token: string };
  now?: () => number;
}) {
  const taskId = uuid(execution.taskId);
  if (
    typeof execution.token !== 'string' ||
    execution.token.length !== 64 ||
    /[^0-9a-f]/.test(execution.token)
  )
    throw new AgentError('cancelled');
  const binding = Object.freeze({
    taskId,
    executionHash: await digest(execution.token),
  });
  async function read(signal?: AbortSignal) {
    signal?.throwIfAborted();
    const started = performance.now();
    const row = (
      await db
        .prepare(
          `${EXECUTION_CLOCK_SQL}
      SELECT t.owner_id, t.deadline_at, d.expires_at AS device_expires_at, clock.time AS observed_at,
        (${EXECUTION_ACTIVE_SQL}) AS active
      FROM agent_cloud_tasks t JOIN agent_devices d ON d.id = t.device_id AND d.owner_id = t.owner_id,
        clock WHERE t.id = ? AND t.execution_hash = ?`,
        )
        .bind(timestamp(now), binding.taskId, binding.executionHash)
        .all()
    ).results[0];
    signal?.throwIfAborted();
    if (!row) throw new AgentError('cancelled');
    // 保守扣除全部查询往返耗时；数据库执行前的等待绝不能获得新时限。
    const observed =
      Math.max(timestamp(now), Number(row.observed_at)) + (performance.now() - started);
    const remaining = Number(row.deadline_at) - observed;
    if (remaining <= 0) throw new AgentError('deadline');
    const deviceRemaining = Number(row.device_expires_at) - observed;
    if (row.active !== 1 || deviceRemaining <= 0) throw new AgentError('cancelled');
    return {
      ownerId: String(row.owner_id),
      remainingMs: Math.min(30000, remaining, deviceRemaining),
    };
  }
  const initial = await read();
  return {
    ownerId: initial.ownerId,
    binding,
    async check(signal?: AbortSignal) {
      return (await read(signal)).remainingMs;
    },
    async beginDispatch(requestId: string, reservationId: string, signal: AbortSignal) {
      signal.throwIfAborted();
      const started = performance.now();
      const results = await db.batch([
        db
          .prepare(
            `${EXECUTION_CLOCK_SQL}
        UPDATE agent_budget_reservations SET dispatch_state = 'may_have_dispatched'
        WHERE id = ? AND owner_id = ? AND task_id = ? AND request_id = ? AND execution_hash = ?
          AND dispatch_state = 'prepared' AND state = 'reserved'
          AND EXISTS (SELECT 1 FROM agent_cloud_tasks t, clock
            WHERE t.id = agent_budget_reservations.task_id AND t.owner_id = agent_budget_reservations.owner_id
              AND t.execution_hash = agent_budget_reservations.execution_hash AND ${EXECUTION_ACTIVE_SQL})`,
          )
          .bind(
            timestamp(now),
            reservationId,
            initial.ownerId,
            binding.taskId,
            requestId,
            binding.executionHash,
          ),
        db
          .prepare(
            `${EXECUTION_CLOCK_SQL} SELECT
          MIN(t.deadline_at, d.expires_at) AS expires_at, clock.time AS observed_at
          FROM agent_budget_reservations r JOIN agent_cloud_tasks t ON t.id = r.task_id AND t.owner_id = r.owner_id
          JOIN agent_devices d ON d.id = t.device_id AND d.owner_id = t.owner_id, clock
          WHERE r.id = ? AND r.owner_id = ? AND r.execution_hash = ?`,
          )
          .bind(timestamp(now), reservationId, initial.ownerId, binding.executionHash),
      ]);
      signal.throwIfAborted();
      const row = results[1]?.results[0];
      if (
        results[0]?.meta.changes !== 1 ||
        !row ||
        Number(row.expires_at) <=
          Math.max(timestamp(now), Number(row.observed_at)) + (performance.now() - started)
      )
        throw new AgentError('cancelled');
    },
  };
}

// 可信维护入口：游标遍历只处理状态与费用，不读取任务输入、不派发模型。
export async function recoverExecutions({
  db,
  now = Date.now,
  limit = 100,
  cursor = '',
}: {
  db: BudgetDatabase;
  now?: () => number;
  limit?: number;
  cursor?: string;
}) {
  try {
    if (
      !Number.isSafeInteger(limit) ||
      limit < 1 ||
      limit > 100 ||
      typeof cursor !== 'string' ||
      cursor.length > 80 ||
      /[^a-zA-Z0-9_-]/.test(cursor)
    )
      throw new AgentError('budget_unavailable');
    const rows = (
      await db
        .prepare(
          `SELECT r.id, r.owner_id, u.applied FROM agent_budget_reservations r
      LEFT JOIN agent_usage_receipts u ON u.reservation_id = r.id
      WHERE r.execution_hash IS NOT NULL AND r.id > ?
        AND (r.state IN ('reserved','uncertain') OR u.applied = 0)
      ORDER BY r.id LIMIT ?`,
        )
        .bind(cursor, limit + 1)
        .all()
    ).results;
    let receiptsApplied = 0;
    let closed = 0;
    let uncertain = 0;
    for (const row of rows.slice(0, limit)) {
      const id = String(row.id);
      if (row.applied === 0) {
        await applyUsageReceipt({
          db,
          ownerId: String(row.owner_id),
          reservationId: id,
        });
        receiptsApplied++;
      }
      const statements = (['prepared', 'may_have_dispatched'] as const).map((state) =>
        db
          .prepare(
            `${EXECUTION_CLOCK_SQL} UPDATE agent_budget_reservations
          SET state = ?, dispatch_state = ? WHERE id = ? AND execution_hash IS NOT NULL
            AND dispatch_state = ? AND state IN ('reserved','uncertain')
            AND NOT EXISTS (SELECT 1 FROM agent_cloud_tasks t, clock
              WHERE t.id = agent_budget_reservations.task_id AND t.owner_id = agent_budget_reservations.owner_id
                AND t.execution_hash = agent_budget_reservations.execution_hash AND ${EXECUTION_ACTIVE_SQL})`,
          )
          .bind(
            timestamp(now),
            state === 'prepared' ? 'released' : 'uncertain',
            state === 'prepared' ? 'closed_not_dispatched' : state,
            id,
            state,
          ),
      );
      const results = await db.batch(statements);
      closed += results[0]?.meta.changes ?? 0;
      uncertain += results[1]?.meta.changes ?? 0;
    }
    return {
      processed: Math.min(rows.length, limit),
      receiptsApplied,
      closed,
      uncertain,
      nextCursor: rows.length > limit ? String(rows[limit - 1]!.id) : null,
    };
  } catch {
    throw new AgentError('budget_unavailable');
  }
}

// Free 宿主每次 invocation 只调用一页；最坏 1 + 7×5 = 36 条 SQL。
// 不能在同一次调用里循环游标；其他业务查询和 CPU 仍由宿主另行预算。
export function recoverFreeExecutions(
  options: Omit<Parameters<typeof recoverExecutions>[0], 'limit'>,
) {
  return recoverExecutions({ ...options, limit: 5 });
}
