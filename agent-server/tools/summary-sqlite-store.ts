import { DatabaseSync } from 'node:sqlite';
import type { Principal } from '../src/device-auth.ts';
import type { SummaryRow, SummaryStore } from '../src/summary-service.ts';
import { encodeSummaryRow } from '../src/summary-row.ts';

/** 仅本地隔离验证，未接 Worker，也不承担生产数据库迁移。 */
export class SummarySqliteStore implements SummaryStore {
  private readonly db: DatabaseSync;

  constructor(path: string) {
    this.db = new DatabaseSync(path);
    this.db.exec(`CREATE TABLE IF NOT EXISTS summary_requests (
      owner_id TEXT NOT NULL, device_id TEXT NOT NULL, request_id TEXT NOT NULL,
      snapshot TEXT NOT NULL, PRIMARY KEY (owner_id, device_id, request_id)
    ) STRICT`);
  }

  close() { this.db.close(); }

  async read(principal: Principal, id: string): Promise<SummaryRow | null> {
    return this.readRow(principal, id);
  }

  async create(principal: Principal, row: SummaryRow) {
    return this.transaction(() => {
      const existing = this.readRow(principal, row.requestId);
      if (existing) return { row: existing, created: false };
      const snapshot = this.encode(row);
      this.db.prepare('INSERT INTO summary_requests VALUES (?, ?, ?, ?)')
        .run(principal.ownerId, principal.deviceId, row.requestId, snapshot);
      return { row: structuredClone(row), created: true };
    });
  }

  async change(principal: Principal, id: string, change: (row: SummaryRow | null) => SummaryRow) {
    return this.transaction(() => {
      const current = this.readRow(principal, id);
      const row = change(current === null ? null : structuredClone(current));
      if (row.requestId !== id) throw new Error('请求身份不匹配');
      if (current && (row.inputHash !== current.inputHash || row.fingerprint !== current.fingerprint))
        throw new Error('请求绑定不可修改');
      const snapshot = this.encode(row);
      this.db.prepare(`INSERT INTO summary_requests VALUES (?, ?, ?, ?)
        ON CONFLICT(owner_id, device_id, request_id) DO UPDATE SET snapshot = excluded.snapshot`)
        .run(principal.ownerId, principal.deviceId, id, snapshot);
      return structuredClone(row);
    });
  }

  private transaction<T>(action: () => T): T {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = action();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  private readRow(principal: Principal, id: string): SummaryRow | null {
    const stored = this.db.prepare(`SELECT snapshot FROM summary_requests
      WHERE owner_id = ? AND device_id = ? AND request_id = ?`)
      .get(principal.ownerId, principal.deviceId, id);
    if (!stored) return null;
    const row = JSON.parse(String(stored.snapshot)) as SummaryRow;
    this.encode(row);
    if (row.requestId !== id) throw new Error('存储请求身份不匹配');
    return row;
  }

  private encode(row: SummaryRow): string {
    return encodeSummaryRow(row);
  }
}
