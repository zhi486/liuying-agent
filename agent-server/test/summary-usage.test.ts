import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createSummaryUsage } from '../src/summary-usage.ts';
import { createSummaryService } from '../src/summary-service.ts';
import { SummarySqliteStore } from '../tools/summary-sqlite-store.ts';

test('摘要鉴权与独立记账：去重、撤权、迟到回执、冲突及未知估算', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  database.sqlite.exec(readFileSync(new URL('../tools/summary-runtime/ledger.sql', import.meta.url), 'utf8'));
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('synthetic',1,1)");
  const admin = createDeviceAdmin({ db: database.db });
  const device = await admin.issue('synthetic');
  const ledger = createSummaryUsage(database.db);
  await assert.rejects(ledger.authenticate('Bearer synthetic'));
  const p = await ledger.authenticate(`Bearer ${device.token}`);
  const id = crypto.randomUUID(), hash = 'a'.repeat(64);
  const rate = { input: 300000, output: 1200000 };
  const results = await Promise.all([ledger.begin(p, id, hash, rate), ledger.begin(p, id, hash, rate)]);
  assert.equal(results.filter(Boolean).length, 1);
  assert.deepEqual(await ledger.estimate(p, id), { purpose: 'conversation_summary', currency: 'USD',
    inputTokens: null, outputTokens: null, estimatedMicros: null, receiptConflict: false });
  await admin.revoke(p.ownerId, p.deviceId);
  await assert.rejects(ledger.authenticate(`Bearer ${device.token}`));
  assert.equal(await ledger.begin(p, crypto.randomUUID(), hash, rate), false);
  await ledger.record(p, id, hash, { inputTokens: 100, outputTokens: 40 });
  await ledger.record(p, id, hash, { inputTokens: 100, outputTokens: 40 });
  assert.equal((await ledger.estimate(p, id)).estimatedMicros, 78);
  await assert.rejects(ledger.record(p, id, hash, { inputTokens: 200, outputTokens: 40 }));
  const estimate = await ledger.estimate(p, id);
  assert.equal(estimate.inputTokens, 100);
  assert.equal(estimate.receiptConflict, true);
  await assert.rejects(ledger.record(p, id, 'b'.repeat(64), { inputTokens: 100, outputTokens: 40 }));
  await assert.rejects(ledger.estimate({ ...p, deviceId: 'other' }, id));
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_budget_reservations').get()!.n, 0);
  assert.equal(database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted, 1);
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM summary_usage').get()!.n, 1);
});

test('撤权后重新签发的新设备代次不能接管旧设备请求', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  database.sqlite.exec(readFileSync(new URL('../tools/summary-runtime/ledger.sql', import.meta.url), 'utf8'));
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',1,0)");
  const admin = createDeviceAdmin({ db: database.db });
  const oldDevice = await admin.issue('owner');
  const oldPrincipal = await createSummaryUsage(database.db).authenticate(`Bearer ${oldDevice.token}`);
  const ledger = createSummaryUsage(database.db);
  const id = crypto.randomUUID(), hash = 'c'.repeat(64);
  assert.equal(await ledger.begin(oldPrincipal, id, hash, { input: 300000, output: 1200000 }), true);
  await admin.revoke('owner', oldPrincipal.deviceId);
  const newDevice = await admin.issue('owner');
  const newPrincipal = await ledger.authenticate(`Bearer ${newDevice.token}`);
  assert.notEqual(newPrincipal.deviceId, oldPrincipal.deviceId);
  await assert.rejects(ledger.estimate(newPrincipal, id));
  await assert.rejects(ledger.record(newPrincipal, id, hash, { inputTokens: 3, outputTokens: 1 }));
  assert.equal(await ledger.begin(newPrincipal, id, hash, { input: 300000, output: 1200000 }), true);
  await ledger.record(newPrincipal, id, hash, { inputTokens: 100, outputTokens: 40 });
  await ledger.record(oldPrincipal, id, hash, { inputTokens: 3, outputTokens: 1 });
  assert.equal((await ledger.estimate(oldPrincipal, id)).estimatedMicros, 3);
  assert.equal((await ledger.estimate(newPrincipal, id)).estimatedMicros, 78);
});

test('摘要处理器联合记账：保存回执失败后查询补记，不重新生成', async (t) => {
  const database = openBudgetDatabase();
  const store = new SummarySqliteStore(':memory:');
  t.after(() => { store.close(); database.close(); });
  database.sqlite.exec(readFileSync(new URL('../tools/summary-runtime/ledger.sql', import.meta.url), 'utf8'));
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',1,1)");
  const device = await createDeviceAdmin({ db: database.db }).issue('owner');
  const ledger = createSummaryUsage(database.db);
  const p = await ledger.authenticate(`Bearer ${device.token}`);
  let calls = 0;
  const handle = createSummaryService({ store, authenticate: ledger.authenticate,
    accounting: { begin: (p, id, hash) => ledger.begin(p, id, hash, { input: 300000, output: 1200000 }),
      record: ledger.record },
    model: { async generate() { calls++; return { text: '合成摘要', inputTokens: 100, outputTokens: 40 }; } } });
  const id = crypto.randomUUID();
  const input = { version: 'summary_v1', requestId: id, fingerprint: 'a'.repeat(64), approved: true,
    sources: [{ requestId: crypto.randomUUID(), prompt: '合成问题', answer: '合成回答' }] };
  const request = (method: string, suffix = '', body?: unknown) => handle(new Request(
    `https://synthetic.example/v1/agent/summaries${suffix}`, { method,
      headers: { Authorization: `Bearer ${device.token}`, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) }));
  const blockedId = crypto.randomUUID();
  database.sqlite.exec(`CREATE TRIGGER reject_registration BEFORE INSERT ON summary_usage
    BEGIN SELECT RAISE(ABORT, 'synthetic registration failure'); END`);
  assert.equal((await request('POST', '', { ...input, requestId: blockedId })).status, 503);
  assert.equal(calls, 0);
  database.sqlite.exec('DROP TRIGGER reject_registration');
  const blocked = await (await request('POST', '', { ...input, requestId: blockedId })).json();
  assert.equal(blocked.state, 'attempted');
  assert.equal(calls, 0);
  await assert.rejects(ledger.estimate(p, blockedId));
  database.sqlite.exec(`CREATE TRIGGER reject_receipt BEFORE UPDATE OF input_tokens ON summary_usage
    BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END`);
  assert.equal((await request('POST', '', input)).status, 503);
  assert.equal(calls, 1);
  assert.equal((await store.read(p, id))?.state, 'completed');
  assert.equal((await ledger.estimate(p, id)).estimatedMicros, null);
  database.sqlite.exec('DROP TRIGGER reject_receipt');
  const restored = await (await request('GET', `/${id}`)).json();
  assert.equal(restored.state, 'completed');
  assert.equal((await ledger.estimate(p, id)).estimatedMicros, 78);
  await request('POST', '', input);
  assert.equal(calls, 1);
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM summary_usage').get()!.n, 1);
});

test('生成后撤销设备不交付正文，结果保存失败仍保留独立用量', async (t) => {
  for (const mode of ['revoke', 'save-failure', 'revoke-after-save'] as const) {
    const database = openBudgetDatabase();
    const store = new SummarySqliteStore(':memory:');
    t.after(() => { store.close(); database.close(); });
    database.sqlite.exec(readFileSync(new URL('../tools/summary-runtime/ledger.sql', import.meta.url), 'utf8'));
    database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',1,0)");
    const admin = createDeviceAdmin({ db: database.db });
    const device = await admin.issue('owner');
    const ledger = createSummaryUsage(database.db);
    const p = await ledger.authenticate(`Bearer ${device.token}`);
    const id = crypto.randomUUID();
    const input = { version: 'summary_v1', requestId: id, fingerprint: 'a'.repeat(64), approved: true,
      sources: [{ requestId: crypto.randomUUID(), prompt: '合成问题', answer: '合成回答' }] };
    let calls = 0;
    const handle = createSummaryService({
      store: { create: store.create.bind(store), read: store.read.bind(store),
        change: async (principal, requestId, change) => {
          if (mode === 'save-failure') throw new Error('合成保存失败');
          const saved = await store.change(principal, requestId, change);
          if (mode === 'revoke-after-save' && saved.state === 'completed')
            await admin.revoke(p.ownerId, p.deviceId);
          return saved;
        } },
      authenticate: ledger.authenticate,
      accounting: { begin: (p, id, hash) => ledger.begin(p, id, hash, { input: 300000, output: 1200000 }), record: ledger.record },
      model: { async generate() {
        calls++;
        if (mode === 'revoke') await admin.revoke(p.ownerId, p.deviceId);
        return { text: '不得泄露的合成摘要', inputTokens: 100, outputTokens: 40 };
      } },
    });
    const request = () => handle(new Request('https://synthetic.example/v1/agent/summaries', { method: 'POST',
      headers: { Authorization: `Bearer ${device.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input) }));
    const response = await request();
    assert.equal(response.status, mode === 'save-failure' ? 503 : 401);
    assert.ok(!(await response.text()).includes('不得泄露'));
    assert.equal((await ledger.estimate(p, id)).estimatedMicros, 78);
    const saved = await store.read(p, id);
    assert.equal(saved?.state, mode === 'save-failure' ? 'attempted' : 'withdrawn');
    assert.equal(saved?.summary, null);
    await request();
    assert.equal(calls, 1);
  }
});
