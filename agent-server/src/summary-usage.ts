import type { BudgetDatabase } from './d1.ts';
import { createDeviceAuth, type Principal } from './device-auth.ts';
import { CloudError, uuid, DATABASE_TIME_SQL } from './cloud-task-contracts.ts';

type Usage = Readonly<{ inputTokens: number; outputTokens: number }>;
const integer = (n: number) => Number.isSafeInteger(n) && n >= 0;

/** 未挂载的可信记账端口；不接受HTTP或模型自报的主体及回执。 */
export function createSummaryUsage(db: BudgetDatabase) {
  const auth = createDeviceAuth({ db });
  const keys = (p: Principal, id: string) => [p.ownerId, p.deviceId, uuid(id)] as const;
  return {
    authenticate: auth.authenticate,
    async begin(p: Principal, id: string, hash: string, rate: { input: number; output: number }) {
      if (!/^[0-9a-f]{64}$/.test(hash) || !integer(rate.input) || rate.input < 1 ||
          !integer(rate.output) || rate.output < 1) throw new CloudError('invalid_request');
      const result = await db.prepare(`INSERT INTO summary_usage
        (owner_id, device_id, request_id, input_hash, input_rate, output_rate)
        SELECT owner_id, id, ?, ?, ?, ? FROM agent_devices
        WHERE owner_id=? AND id=? AND revoked_at IS NULL
          AND created_at <= ${DATABASE_TIME_SQL} AND expires_at > ${DATABASE_TIME_SQL}
        ON CONFLICT(owner_id,device_id,request_id) DO NOTHING`)
        .bind(uuid(id), hash, rate.input, rate.output, p.ownerId, p.deviceId).run();
      return result.meta.changes === 1;
    },
    async record(p: Principal, id: string, hash: string, usage: Usage) {
      if (!/^[0-9a-f]{64}$/.test(hash) || !integer(usage.inputTokens) || !integer(usage.outputTokens))
        throw new CloudError('invalid_request');
      // 迟到回执不依赖设备仍有效，但必须匹配已存在的请求绑定。
      const result = await db.batch([
        db.prepare(`UPDATE summary_usage SET input_tokens=?, output_tokens=?
          WHERE owner_id=? AND device_id=? AND request_id=? AND input_hash=? AND input_tokens IS NULL`)
          .bind(usage.inputTokens, usage.outputTokens, ...keys(p, id), hash),
        db.prepare(`UPDATE summary_usage SET conflict=1
          WHERE owner_id=? AND device_id=? AND request_id=? AND input_hash=?
          AND (input_tokens <> ? OR output_tokens <> ?)`)
          .bind(...keys(p, id), hash, usage.inputTokens, usage.outputTokens),
        db.prepare(`SELECT input_tokens,output_tokens FROM summary_usage
          WHERE owner_id=? AND device_id=? AND request_id=? AND input_hash=?`)
          .bind(...keys(p, id), hash),
      ]);
      const row = result[2]?.results[0];
      if (!row || row.input_tokens !== usage.inputTokens || row.output_tokens !== usage.outputTokens)
        throw new CloudError('conflict');
    },
    async estimate(p: Principal, id: string) {
      const row = (await db.prepare(`SELECT input_tokens,output_tokens,input_rate,output_rate,conflict
        FROM summary_usage WHERE owner_id=? AND device_id=? AND request_id=?`)
        .bind(...keys(p, id)).all()).results[0];
      if (!row) throw new CloudError('not_found');
      const inputTokens = row.input_tokens === null ? null : Number(row.input_tokens);
      const outputTokens = row.output_tokens === null ? null : Number(row.output_tokens);
      let estimatedMicros: number | null = null;
      if (inputTokens !== null && outputTokens !== null) {
        const amount = (BigInt(inputTokens) * BigInt(Number(row.input_rate)) +
          BigInt(outputTokens) * BigInt(Number(row.output_rate)) + 999999n) / 1000000n;
        if (amount <= BigInt(Number.MAX_SAFE_INTEGER)) estimatedMicros = Number(amount);
      }
      return { purpose: 'conversation_summary' as const, currency: 'USD' as const,
        inputTokens, outputTokens, estimatedMicros, receiptConflict: row.conflict === 1 };
    },
  };
}
