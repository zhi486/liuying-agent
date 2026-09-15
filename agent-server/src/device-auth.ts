import type { BudgetDatabase } from './d1.ts';
import {
  CloudError,
  cloudOperation,
  identifier,
  timestamp,
  DATABASE_TIME_SQL,
} from './cloud-task-contracts.ts';

export type Principal = Readonly<{ ownerId: string; deviceId: string }>;
type Options = { db: BudgetDatabase; now?: () => number };

// 秘密只供可信调用方使用，数据库和日志均不保存原值。
export function randomSecret(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('');
}

export async function digest(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function createDeviceAuth({ db, now = Date.now }: Options) {
  return {
    authenticate(authorization: unknown): Promise<Principal> {
      return cloudOperation(async () => {
        if (
          typeof authorization !== 'string' ||
          authorization.length !== 75 ||
          !/^Bearer lyd_[0-9a-f]{64}$/.test(authorization)
        )
          throw new CloudError('unauthenticated');
        const hash = await digest(authorization.slice(7));
        const time = timestamp(now);
        const row = (
          await db
            .prepare(
              `SELECT id, owner_id, expires_at FROM agent_devices
          WHERE token_hash = ? AND created_at <= MAX(?, ${DATABASE_TIME_SQL}) AND expires_at > MAX(?, ${DATABASE_TIME_SQL}) AND revoked_at IS NULL`,
            )
            .bind(hash, time, time)
            .all()
        ).results[0];
        if (!row || Number(row.expires_at) <= timestamp(now))
          throw new CloudError('unauthenticated');
        return Object.freeze({ ownerId: String(row.owner_id), deviceId: String(row.id) });
      });
    },
  };
}

// 此工厂仅允许可信管理流程持有；普通设备认证不授予管理权。没有公开管理路由。
export function createDeviceAdmin({ db, now = Date.now }: Options) {
  return {
    issue(ownerId: string) {
      return cloudOperation(async () => {
        identifier(ownerId);
        const token = 'lyd_' + randomSecret();
        const hash = await digest(token);
        const time = timestamp(now);
        const deviceId = crypto.randomUUID();
        const expiresAtMillis = time + 7776000000;
        const result = await db
          .prepare(
            `INSERT INTO agent_devices
          (id, owner_id, token_hash, created_at, expires_at)
          SELECT ?, owner_id, ?, ?, ? FROM agent_budget_accounts WHERE owner_id = ?`,
          )
          .bind(deviceId, hash, time, expiresAtMillis, ownerId)
          .run();
        if (result.meta.changes !== 1) throw new CloudError('not_found');
        return { deviceId, token, expiresAtMillis };
      });
    },
    revoke(ownerId: string, deviceId: string) {
      return cloudOperation(async () => {
        identifier(ownerId);
        identifier(deviceId);
        const time = timestamp(now);
        const results = await db.batch([
          db
            .prepare(
              `UPDATE agent_devices SET revoked_at = ? WHERE id = ? AND owner_id = ? AND revoked_at IS NULL`,
            )
            .bind(time, deviceId, ownerId),
          db
            .prepare(
              `UPDATE agent_cloud_tasks SET status = 'cancelled', reason = 'device_revoked', updated_at = MAX(updated_at, ?)
            WHERE device_id = ? AND owner_id = ? AND status IN ('queued','running')`,
            )
            .bind(time, deviceId, ownerId),
          db
            .prepare('SELECT id FROM agent_devices WHERE id = ? AND owner_id = ?')
            .bind(deviceId, ownerId),
        ]);
        if (!results[2]?.results.length) throw new CloudError('not_found');
      });
    },
  };
}
