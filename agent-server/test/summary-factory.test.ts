import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createSummaryUsage } from '../src/summary-usage.ts';
import { createSummaryApplication } from '../src/summary-factory.ts';
import { SummarySqliteStore } from '../tools/summary-sqlite-store.ts';

for (const mode of ['success', 'revoke', 'receipt-failure'] as const)
  test('摘要服务工厂隔离联合：' + mode, async (t) => {
    const database = openBudgetDatabase();
    const store = new SummarySqliteStore(':memory:');
    t.after(() => { store.close(); database.close(); });
    database.sqlite.exec(readFileSync(new URL('../tools/summary-runtime/ledger.sql', import.meta.url), 'utf8'));
    database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('synthetic',1,1)");
    const admin = createDeviceAdmin({ db: database.db });
    const device = await admin.issue('synthetic');
    const principal = await createSummaryUsage(database.db).authenticate('Bearer ' + device.token);
    let calls = 0;
    let counts = 0;
    const rate = { input: 300000, output: 1200000 };
    const app = createSummaryApplication({ db: database.db, store, rate,
      model: { apiKey: 'synthetic-key', countInputTokens: async () => { counts++; return 100; },
        fetch: async (_url, init) => {
          calls++;
          const body = JSON.parse(String(init?.body));
          assert.deepEqual(body.tools, []);
          assert.equal(init?.redirect, 'manual');
          if (mode === 'revoke') await admin.revoke(principal.ownerId, principal.deviceId);
          return Response.json({ choices: [{ index: 0, finish_reason: 'stop',
            message: { role: 'assistant', content: '合成摘要' } }],
          usage: { prompt_tokens: 100, completion_tokens: 40, total_tokens: 140 } });
        } } });
    // 外部对象变化不得追溯修改工厂绑定的费率。
    rate.input = 1;
    const id = crypto.randomUUID();
    const input = { version: 'summary_v1', requestId: id, fingerprint: 'a'.repeat(64), approved: true,
      sources: [{ requestId: crypto.randomUUID(), prompt: '合成问题', answer: '合成回答' }] };
    const request = (method = 'POST', token = device.token) => app.handle(new Request(
      'https://synthetic.example/v1/agent/summaries' + (method === 'GET' ? '/' + id : ''), {
        method, headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        ...(method === 'POST' ? { body: JSON.stringify(input) } : {}) }));
    assert.equal((await request('POST', 'invalid')).status, 401);
    assert.equal(calls, 0); assert.equal(counts, 0);
    if (mode === 'receipt-failure') database.sqlite.exec(
      "CREATE TRIGGER reject_receipt BEFORE UPDATE OF input_tokens ON summary_usage BEGIN SELECT RAISE(ABORT, 'synthetic'); END");
    const first = await request();
    assert.equal(first.status, mode === 'success' ? 200 : mode === 'revoke' ? 401 : 503);
    if (mode === 'revoke') {
      assert.ok(!(await first.text()).includes('合成摘要'));
      assert.equal((await store.read(principal, id))?.state, 'withdrawn');
      assert.equal((await app.estimate(principal, id)).estimatedMicros, 78);
    } else {
      if (mode === 'receipt-failure') {
        assert.equal((await app.estimate(principal, id)).estimatedMicros, null);
        database.sqlite.exec('DROP TRIGGER reject_receipt');
      }
      const restored = await request('GET');
      assert.equal(restored.status, 200);
      const result = await restored.json();
      assert.equal(result.state, 'completed'); assert.equal(result.summary, '合成摘要');
      assert.equal((await app.estimate(principal, id)).estimatedMicros, 78);
    }
    await request();
    assert.equal(calls, 1); assert.equal(counts, 1);
    assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_budget_reservations').get()!.n, 0);
    assert.equal(database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted, 1);
  });
