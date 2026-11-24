import type { DurableObjectStorage } from '@cloudflare/workers-types';
import type { Principal } from './device-auth.ts';
import type { SummaryRow, SummaryStore } from './summary-service.ts';
import { encodeSummaryRow } from './summary-row.ts';

/** 未挂载；仅在专用摘要对象中初始化，不迁移现有运行时数据库。 */
export function createSummaryDoStore(storage: DurableObjectStorage): SummaryStore {
  storage.sql.exec(`CREATE TABLE IF NOT EXISTS summaries (
    owner TEXT NOT NULL, device TEXT NOT NULL, id TEXT NOT NULL, snapshot TEXT NOT NULL,
    PRIMARY KEY(owner, device, id)) STRICT`).toArray();
  function read(p: Principal, id: string): SummaryRow | null {
    const stored = storage.sql.exec<{ snapshot: string }>(
      'SELECT snapshot FROM summaries WHERE owner=? AND device=? AND id=?',
      p.ownerId, p.deviceId, id).toArray()[0];
    if (!stored) return null;
    const row = JSON.parse(stored.snapshot) as SummaryRow;
    encodeSummaryRow(row);
    if (row.requestId !== id) throw new Error('请求身份不匹配');
    return row;
  }
  function save(p: Principal, row: SummaryRow) {
    storage.sql.exec(`INSERT INTO summaries VALUES (?, ?, ?, ?)
      ON CONFLICT(owner,device,id) DO UPDATE SET snapshot=excluded.snapshot`,
      p.ownerId, p.deviceId, row.requestId, encodeSummaryRow(row)).toArray();
  }
  return {
    async read(p, id) { return read(p, id); },
    async create(p, row) {
      const result = storage.transactionSync(() => {
        const current = read(p, row.requestId);
        if (current) return { row: current, created: false };
        save(p, row);
        return { row: structuredClone(row), created: true };
      });
      // 派发许可必须等待持久写入完成，不仅依赖内存事务结果。
      await storage.sync();
      return result;
    },
    async change(p, id, update) {
      const result = storage.transactionSync(() => {
        const current = read(p, id);
        const row = update(structuredClone(current));
        if (row.requestId !== id || (current &&
            (row.inputHash !== current.inputHash || row.fingerprint !== current.fingerprint)))
          throw new Error('请求绑定不可修改');
        save(p, row);
        return structuredClone(row);
      });
      await storage.sync();
      return result;
    },
  };
}
