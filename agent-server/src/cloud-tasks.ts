import type { BudgetDatabase, BudgetStatement } from './d1.ts';
import { createDeviceAuth, digest, randomSecret, type Principal } from './device-auth.ts';
import {
  cloudOperation,
  CloudError,
  timestamp,
  uuid,
  parseSubmission,
  parseResult,
  type CloudTaskView,
  DATABASE_TIME_SQL,
} from './cloud-task-contracts.ts';

const DAY = 86400000;
const ACTIVE = "status IN ('queued','running')";
const VALID_DEVICE = `EXISTS (SELECT 1 FROM agent_devices d WHERE d.id = ? AND d.owner_id = ? AND d.created_at <= MAX(?, ${DATABASE_TIME_SQL}) AND d.expires_at > MAX(?, ${DATABASE_TIME_SQL}) AND d.revoked_at IS NULL)`;
type Context = Principal & { time: number };
const gateValues = (context: Context) =>
  [context.deviceId, context.ownerId, context.time, context.time] as const;

function view(row: Record<string, unknown>, time: number): CloudTaskView {
  const expired =
    row.result_state === 'available' &&
    Number(row.result_expires_at) <= Math.max(time, Number(row.observed_at));
  let result: CloudTaskView['result'] = null;
  if (row.result_state === 'available' && !expired) {
    try {
      result = parseResult(JSON.parse(String(row.result_json)));
    } catch {
      throw new CloudError('unavailable');
    }
  }
  return {
    id: String(row.id),
    status: row.status as CloudTaskView['status'],
    reason: row.reason as string | null,
    acceptedAtMillis: Number(row.accepted_at),
    deadlineAtMillis: Number(row.deadline_at),
    updatedAtMillis: Number(row.updated_at),
    resultState: expired ? 'expired' : (row.result_state as CloudTaskView['resultState']),
    result,
  };
}

export function createCloudTasks({
  db,
  now = Date.now,
}: {
  db: BudgetDatabase;
  now?: () => number;
}) {
  const auth = createDeviceAuth({ db, now });
  function cleanup(time: number, context?: Context): BudgetStatement[] {
    const gate = context ? `owner_id = ? AND ${VALID_DEVICE}` : '1 = 1';
    const values = context ? [context.ownerId, ...gateValues(context)] : [];
    return [
      db
        .prepare(
          `UPDATE agent_cloud_tasks SET status = 'timed_out', reason = 'deadline', updated_at = MAX(updated_at, ?, ${DATABASE_TIME_SQL})
        WHERE ${ACTIVE} AND deadline_at <= MAX(?, ${DATABASE_TIME_SQL}) AND ${gate}`,
        )
        .bind(time, time, ...values),
      db
        .prepare(
          `UPDATE agent_cloud_tasks SET result_json = NULL, result_state = 'expired'
        WHERE result_state = 'available' AND result_expires_at <= MAX(?, ${DATABASE_TIME_SQL}) AND ${gate}`,
        )
        .bind(time, ...values),
      db
        .prepare(
          `UPDATE agent_cloud_tasks SET payload_digest = NULL
        WHERE payload_digest IS NOT NULL AND payload_expires_at <= MAX(?, ${DATABASE_TIME_SQL}) AND ${gate}`,
        )
        .bind(time, ...values),
    ];
  }
  function select(context: Context, column: 'id' | 'client_request_id', key: string) {
    return db
      .prepare(
        `SELECT *, ${DATABASE_TIME_SQL} AS observed_at FROM agent_cloud_tasks WHERE owner_id = ? AND ${column} = ? AND ${VALID_DEVICE}`,
      )
      .bind(context.ownerId, key, ...gateValues(context));
  }
  async function transaction(context: Context, statements: BudgetStatement[]) {
    const results = await db.batch([
      ...cleanup(context.time, context),
      ...statements,
      db.prepare(`SELECT 1 WHERE ${VALID_DEVICE}`).bind(...gateValues(context)),
    ]);
    if (!results.at(-1)?.results.length) throw new CloudError('unauthenticated');
    return results.slice(3, -1);
  }
  function required(row: Record<string, unknown> | undefined) {
    if (!row) throw new CloudError('not_found');
    return row;
  }
  async function read(authorization: unknown, column: 'id' | 'client_request_id', key: unknown) {
    const principal = await auth.authenticate(authorization);
    const id = uuid(key);
    const context = { ...principal, time: timestamp(now) };
    const result = await transaction(context, [select(context, column, id)]);
    return view(required(result[0]?.results[0]), timestamp(now));
  }
  async function change(
    authorization: unknown,
    taskId: unknown,
    operation: 'cancel' | 'withdraw' | 'acknowledge',
  ) {
    const principal = await auth.authenticate(authorization);
    const id = uuid(taskId);
    const context = { ...principal, time: timestamp(now) };
    const clause =
      operation === 'cancel'
        ? `status = 'cancelled', reason = 'cancelled'`
        : operation === 'withdraw'
          ? `status = CASE WHEN ${ACTIVE} THEN 'cancelled' ELSE status END,
           reason = CASE WHEN ${ACTIVE} THEN 'consent_revoked' ELSE reason END, result_json = NULL, result_state = 'withdrawn'`
          : "result_json = NULL, result_state = 'acknowledged'";
    const condition =
      operation === 'cancel'
        ? ACTIVE
        : operation === 'withdraw'
          ? "result_state <> 'withdrawn'"
          : "result_state = 'available'";
    const results = await transaction(context, [
      db
        .prepare(
          `UPDATE agent_cloud_tasks SET ${clause}, updated_at = MAX(updated_at, ?, ${DATABASE_TIME_SQL})
        WHERE id = ? AND owner_id = ? AND ${condition} AND ${VALID_DEVICE}`,
        )
        .bind(context.time, id, context.ownerId, ...gateValues(context)),
      select(context, 'id', id),
    ]);
    const row = required(results[1]?.results[0]);
    if (operation === 'acknowledge' && (row.status === 'queued' || row.status === 'running'))
      throw new CloudError('inactive');
    return view(row, timestamp(now));
  }
  return {
    accept(authorization: unknown, input: unknown) {
      return cloudOperation(async () => {
        const principal = await auth.authenticate(authorization);
        const { task, clientRequestId } = parseSubmission(input);
        const hash = await digest(JSON.stringify(task));
        const time = timestamp(now);
        const id = crypto.randomUUID();
        const context = { ...principal, time };
        const results = await transaction(context, [
          db
            .prepare(
              `INSERT INTO agent_cloud_tasks
          (id, owner_id, device_id, client_request_id, payload_digest, payload_expires_at, status, accepted_at, deadline_at, updated_at)
          SELECT ?, ?, ?, ?, ?, ?, 'queued', ?, ?, ? WHERE ${VALID_DEVICE}
          AND NOT EXISTS (SELECT 1 FROM agent_cloud_tasks WHERE owner_id = ? AND (client_request_id = ? OR ${ACTIVE}))`,
            )
            .bind(
              id,
              principal.ownerId,
              principal.deviceId,
              clientRequestId,
              hash,
              time + DAY,
              time,
              time + 30000,
              time,
              ...gateValues(context),
              principal.ownerId,
              clientRequestId,
            ),
          select(context, 'client_request_id', clientRequestId),
        ]);
        const row = results[1]?.results[0];
        if (!row) throw new CloudError('busy');
        if (
          row.payload_digest === null ||
          Number(row.payload_expires_at) <= Math.max(timestamp(now), Number(row.observed_at))
        )
          throw new CloudError('request_expired');
        if (row.payload_digest !== hash) throw new CloudError('conflict');
        return { task: view(row, timestamp(now)), created: results[0]?.meta.changes === 1 };
      });
    },
    get: (authorization: unknown, taskId: unknown) =>
      cloudOperation(() => read(authorization, 'id', taskId)),
    findByRequest: (authorization: unknown, requestId: unknown) =>
      cloudOperation(() => read(authorization, 'client_request_id', requestId)),
    cancel: (authorization: unknown, taskId: unknown) =>
      cloudOperation(() => change(authorization, taskId, 'cancel')),
    withdraw: (authorization: unknown, taskId: unknown) =>
      cloudOperation(() => change(authorization, taskId, 'withdraw')),
    acknowledge: (authorization: unknown, taskId: unknown) =>
      cloudOperation(() => change(authorization, taskId, 'acknowledge')),
    // 仅供可信执行器调用，不向设备提供认领和完成的 HTTP 路由。
    claim(authorization: unknown, taskId: unknown) {
      return cloudOperation(async () => {
        const principal = await auth.authenticate(authorization);
        const id = uuid(taskId);
        const token = randomSecret();
        const hash = await digest(token);
        const context = { ...principal, time: timestamp(now) };
        const results = await transaction(context, [
          db
            .prepare(
              `UPDATE agent_cloud_tasks SET status = 'running', execution_hash = ?, updated_at = MAX(updated_at, ?, ${DATABASE_TIME_SQL})
            WHERE id = ? AND owner_id = ? AND device_id = ? AND status = 'queued' AND deadline_at > MAX(?, ${DATABASE_TIME_SQL}) AND ${VALID_DEVICE}`,
            )
            .bind(
              hash,
              context.time,
              id,
              context.ownerId,
              context.deviceId,
              context.time,
              ...gateValues(context),
            ),
          select(context, 'id', id),
        ]);
        required(results[1]?.results[0]);
        if (results[0]?.meta.changes !== 1) throw new CloudError('inactive');
        return { taskId: id, token };
      });
    },
    finish(execution: { taskId: string; token: string }, input: unknown) {
      return cloudOperation(async () => {
        const id = uuid(execution.taskId);
        if (
          typeof execution.token !== 'string' ||
          execution.token.length !== 64 ||
          /[^0-9a-f]/.test(execution.token)
        )
          throw new CloudError('inactive');
        const result = parseResult(input);
        const json = JSON.stringify(result);
        const hash = await digest(execution.token);
        const initial = (
          await db
            .prepare(
              'SELECT owner_id, device_id FROM agent_cloud_tasks WHERE id = ? AND execution_hash = ?',
            )
            .bind(id, hash)
            .all()
        ).results[0];
        if (!initial) throw new CloudError('inactive');
        const time = timestamp(now);
        const context = {
          ownerId: String(initial.owner_id),
          deviceId: String(initial.device_id),
          time,
        };
        const results = await db.batch([
          ...cleanup(time, context),
          db
            .prepare(
              `UPDATE agent_cloud_tasks SET status = ?, reason = ?, result_json = ?, result_state = 'available',
            result_expires_at = MAX(?, ${DATABASE_TIME_SQL} + ${DAY}), updated_at = MAX(updated_at, ?, ${DATABASE_TIME_SQL})
            WHERE id = ? AND execution_hash = ? AND status = 'running' AND deadline_at > MAX(?, ${DATABASE_TIME_SQL}) AND ${VALID_DEVICE}`,
            )
            .bind(
              result.status,
              result.status === 'completed' ? null : result.reason,
              json,
              time + DAY,
              time,
              id,
              hash,
              time,
              ...gateValues(context),
            ),
          select(context, 'id', id),
        ]);
        const row = results[4]?.results[0];
        if (!row || row.result_state !== 'available') throw new CloudError('inactive');
        if (row.result_json !== json) throw new CloudError('conflict');
      });
    },
    // 部署端仍须配置定期调用；该方法不会自动创建云端定时任务。
    purge: () =>
      cloudOperation(async () => {
        await db.batch(cleanup(timestamp(now)));
      }),
  };
}
