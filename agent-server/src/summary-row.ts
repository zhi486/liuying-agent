import { uuid } from './cloud-task-contracts.ts';
import { exactObject } from './tools.ts';
import type { SummaryRow } from './summary-service.ts';

export function encodeSummaryRow(row: SummaryRow): string {
  exactObject(row, ['requestId', 'fingerprint', 'inputHash', 'state', 'remoteFinished', 'summary', 'usage'], 'invalid_task');
  uuid(row.requestId);
  if (row.usage !== null) exactObject(row.usage, ['inputTokens', 'outputTokens'], 'invalid_task');
  if (!/^[0-9a-f]{64}$/.test(row.fingerprint) || !/^[0-9a-f]{64}$/.test(row.inputHash) ||
      !['attempted', 'completed', 'failed', 'withdrawn'].includes(row.state) ||
      typeof row.remoteFinished !== 'boolean' ||
      (row.summary !== null && (typeof row.summary !== 'string' || !row.summary.trim() || row.summary.length > 2000 ||
        /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(row.summary))) ||
      (row.state !== 'completed' && row.summary !== null) ||
      (row.state === 'completed' && (!row.remoteFinished || row.summary === null || row.usage === null)) ||
      (row.state === 'attempted' && (row.remoteFinished || row.usage !== null)) ||
      (row.state === 'failed' && !row.remoteFinished) ||
      (row.usage !== null && (!Number.isSafeInteger(row.usage.inputTokens) || row.usage.inputTokens < 0 ||
        !Number.isSafeInteger(row.usage.outputTokens) || row.usage.outputTokens < 0)))
    throw new Error('摘要账本快照无效');
  return JSON.stringify(row);
}
