import assert from 'node:assert/strict';
import { test } from 'node:test';
import { encodeSummaryRow } from '../src/summary-row.ts';
import type { SummaryRow } from '../src/summary-service.ts';

const completed: SummaryRow = { requestId: '11111111-1111-4111-8111-111111111111',
  fingerprint: 'a'.repeat(64), inputHash: 'b'.repeat(64), state: 'completed',
  remoteFinished: true, summary: '合成摘要', usage: { inputTokens: 1, outputTokens: 1 } };

test('恢复快照拒绝与客户端契约矛盾的尝试及失败状态', () => {
  for (const row of [
    { ...completed, state: 'attempted', summary: null, usage: null },
    { ...completed, state: 'attempted', summary: null, remoteFinished: false },
    { ...completed, state: 'failed', summary: null, remoteFinished: false },
  ] as SummaryRow[]) assert.throws(() => encodeSummaryRow(row));
  assert.doesNotThrow(() => encodeSummaryRow({ ...completed, state: 'attempted', summary: null,
    remoteFinished: false, usage: null }));
  assert.doesNotThrow(() => encodeSummaryRow({ ...completed, state: 'failed', summary: null, usage: null }));
  assert.doesNotThrow(() => encodeSummaryRow({ ...completed, state: 'withdrawn', summary: null,
    remoteFinished: false, usage: null }));
});

test('完成快照必须保留有效用量，正文遵守输出字符边界', () => {
  assert.deepEqual(JSON.parse(encodeSummaryRow(completed)), completed);
  assert.throws(() => encodeSummaryRow({ ...completed, usage: null }));
  for (const code of [0, 1, 8, 11, 12, 14, 31])
    assert.throws(() => encodeSummaryRow({ ...completed, summary: `摘要${String.fromCharCode(code)}` }));
  assert.doesNotThrow(() => encodeSummaryRow({ ...completed, summary: '摘要' + String.fromCharCode(10, 9) + '内容' }));
  // 撤回后可以尚未取得用量，不能将未知写成零。
  assert.doesNotThrow(() => encodeSummaryRow({ ...completed, state: 'withdrawn', summary: null, usage: null }));
});
