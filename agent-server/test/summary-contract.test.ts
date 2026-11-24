import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createSummaryService, type SummaryRow } from '../src/summary-service.ts';
import { encodeSummaryRow } from '../src/summary-row.ts';

test('摘要共享回执样本与实际服务查询响应逐字段一致', async () => {
  const fixture = JSON.parse(readFileSync(new URL('../../contracts/agent/summary-receipts-v1.json', import.meta.url), 'utf8'));
  for (const sample of fixture.cases) {
    const { cost, ...fields } = sample;
    const row: SummaryRow = { ...fields, requestId: fixture.requestId, fingerprint: fixture.fingerprint, inputHash: 'b'.repeat(64) };
    encodeSummaryRow(row);
    const handle = createSummaryService({
      authenticate: async () => ({ ownerId: 'synthetic', deviceId: 'device' }),
      store: { read: async () => row, create: async () => { throw new Error('不应创建'); },
        change: async () => { throw new Error('不应修改'); } },
      model: { generate: async () => { throw new Error('不应生成'); } },
    });
    const response = await handle(new Request(`https://synthetic.example/v1/agent/summaries/${fixture.requestId}`));
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { version: 'summary_v1', purpose: 'conversation_summary',
      requestId: fixture.requestId, fingerprint: fixture.fingerprint, ...sample });
    assert.equal(cost, sample.usage === null ? 'uncertain' : 'usage');
  }
});
