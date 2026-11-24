import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSummaryService, parseSummary, type SummaryRow, type SummaryStore, type SummaryModel } from '../src/summary-service.ts';
import { CloudError } from '../src/cloud-task-contracts.ts';
import type { Principal } from '../src/device-auth.ts';

const requestId = '11111111-1111-4111-8111-111111111111';
const input = { version: 'summary_v1', requestId, fingerprint: 'a'.repeat(64), approved: true,
  sources: [{ requestId: '22222222-2222-4222-8222-222222222222', prompt: '合成问题', answer: '合成回答' }] };

/** 仅同步回调内原子替换的内存替身，不是持久存储证据。 */
class Store implements SummaryStore {
  rows = new Map<string, SummaryRow>();
  fail = false;
  key(principal: Principal, id: string) { return JSON.stringify([principal.ownerId, principal.deviceId, id]); }
  async read(principal: Principal, id: string) { return structuredClone(this.rows.get(this.key(principal, id)) ?? null); }
  async create(principal: Principal, row: SummaryRow) {
    const key = this.key(principal, row.requestId);
    const existing = this.rows.get(key);
    if (existing) return { row: structuredClone(existing), created: false };
    if (this.fail) throw new Error('合成保存失败');
    this.rows.set(key, structuredClone(row));
    return { row: structuredClone(row), created: true };
  }
  async change(principal: Principal, id: string, change: (row: SummaryRow | null) => SummaryRow) {
    const row = change(structuredClone(this.rows.get(this.key(principal, id)) ?? null));
    if (this.fail) throw new Error('合成保存失败');
    this.rows.set(this.key(principal, id), structuredClone(row));
    return structuredClone(row);
  }
}
function fixture(model: SummaryModel, store = new Store(), principal: Principal = { ownerId: 'owner', deviceId: 'device' }) {
  const handle = createSummaryService({ store, model, authenticate: async (authorization) => {
    if (authorization !== 'Bearer synthetic') throw new CloudError('unauthenticated');
    return principal;
  } });
  const call = (method: string, suffix = '', body?: unknown) => handle(new Request(`https://agent.example.com/v1/agent/summaries${suffix}`, {
    method, headers: { Authorization: 'Bearer synthetic', 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }));
  return { call, handle, store };
}

test('严格摘要契约拒绝主体伪造、工具、重复来源及超预算输入', () => {
  const parsed = parseSummary(input);
  assert.ok(Object.isFrozen(parsed.sources[0]));
  for (const value of [{ ...input, ownerId: 'other' }, { ...input, tools: [] }, { ...input, version: 'v1' },
    { ...input, approved: false }, { ...input, sources: [input.sources[0], input.sources[0]] },
    { ...input, sources: [{ ...input.sources[0], prompt: 'a'.repeat(12000) }] }]) assert.throws(() => parseSummary(value));
});

test('重复创建和重建服务不重发，输入变更冲突且用量独立', async () => {
  let calls = 0;
  const model = { async generate() { calls++; return { text: '合成摘要', inputTokens: 10, outputTokens: 2 }; } };
  const first = fixture(model);
  assert.equal((await first.call('POST', '', input)).status, 200);
  const restored = fixture(model, first.store);
  const row = await (await restored.call('POST', '', input)).json();
  assert.equal(row.purpose, 'conversation_summary');
  assert.deepEqual(row.usage, { inputTokens: 10, outputTokens: 2 });
  assert.equal((await restored.call('POST', '', { ...input, fingerprint: 'b'.repeat(64) })).status, 409);
  assert.equal((await restored.call('GET', `/${requestId}`)).status, 200);
  assert.equal(calls, 1);
});

test('并发创建只派发一次，撤回后迟到结果仅保留用量', async () => {
  let finish!: (value: { text: string; inputTokens: number; outputTokens: number }) => void;
  let started!: () => void;
  const ready = new Promise<void>((resolve) => { started = resolve; });
  let calls = 0;
  const f = fixture({ async generate() { calls++; started(); return new Promise((resolve) => { finish = resolve; }); } });
  const pending = f.call('POST', '', input);
  await ready;
  const repeated = await (await f.call('POST', '', input)).json();
  assert.equal(repeated.cost, 'uncertain');
  await f.call('POST', `/${requestId}/withdraw`);
  finish({ text: '不得恢复正文', inputTokens: 7, outputTokens: 1 });
  const result = await (await pending).json();
  assert.equal(result.state, 'withdrawn'); assert.equal(result.summary, null);
  assert.deepEqual(result.usage, { inputTokens: 7, outputTokens: 1 });
  assert.equal(calls, 1);
});

test('领取保存失败零派发，模型失联后重复创建只返回未知', async () => {
  let calls = 0;
  const model = { async generate(): Promise<never> { calls++; throw new Error('合成失联'); } };
  const f = fixture(model);
  f.store.fail = true;
  assert.equal((await f.call('POST', '', input)).status, 503);
  assert.equal(calls, 0);
  f.store.fail = false;
  const row = await (await f.call('POST', '', input)).json();
  assert.equal(row.cost, 'uncertain'); assert.equal(row.remoteFinished, false);
  await f.call('POST', '', input); await f.call('GET', `/${requestId}`);
  assert.equal(calls, 1);
});

test('鉴权失败无存储或模型动作，非法正文不抹实际用量', async () => {
  let calls = 0;
  const f = fixture({ async generate() { calls++; return { text: 'a'.repeat(2001), inputTokens: 1, outputTokens: 2 }; } });
  assert.equal((await f.handle(new Request('https://agent.example.com/v1/agent/summaries', { method: 'POST' }))).status, 401);
  assert.equal(f.store.rows.size, 0); assert.equal(calls, 0);
  const row = await (await f.call('POST', '', input)).json();
  assert.equal(row.state, 'failed'); assert.equal(row.summary, null);
  assert.equal(row.cost, 'usage'); assert.equal(row.remoteFinished, true);
});

test('同时创建的两个处理器只领取一次，结果保存失败不重发', async () => {
  const store = new Store();
  let calls = 0;
  const model = { async generate() { calls++; store.fail = true; return { text: '合成摘要', inputTokens: 3, outputTokens: 1 }; } };
  const first = fixture(model, store);
  const second = fixture(model, store);
  await Promise.all([first.call('POST', '', input), second.call('POST', '', input)]);
  assert.equal(calls, 1);
  store.fail = false;
  const row = await (await second.call('POST', '', input)).json();
  assert.equal(row.cost, 'uncertain'); assert.equal(row.remoteFinished, false);
  assert.equal(calls, 1);
});

test('撤回后生成失败或非法用量返回权威状态，读取失败不回退旧快照', async () => {
  for (const outcome of ['reject', 'invalid', 'read-failure'] as const) {
    let finish!: () => void;
    let started!: () => void;
    const ready = new Promise<void>((resolve) => { started = resolve; });
    let calls = 0;
    const f = fixture({ async generate() {
      calls++;
      started();
      await new Promise<void>((resolve) => { finish = resolve; });
      if (outcome !== 'invalid') throw new Error('合成失联');
      return { text: '不得恢复正文', inputTokens: -1, outputTokens: 1 };
    } });
    const pending = f.call('POST', '', input);
    await ready;
    await f.call('POST', `/${requestId}/withdraw`);
    if (outcome === 'read-failure') f.store.read = async () => { throw new Error('合成读取失败'); };
    finish();
    const response = await pending;
    if (outcome === 'read-failure') {
      assert.equal(response.status, 503);
    } else {
      const row = await response.json();
      assert.equal(row.state, 'withdrawn');
      assert.equal(row.summary, null);
      assert.equal(row.remoteFinished, false);
      assert.equal(row.cost, 'uncertain');
    }
    await f.call('POST', '', input);
    assert.equal(calls, 1);
  }
});

test('同请求ID按用户及设备隔离，其他主体不能读取或撤回', async () => {
  const store = new Store();
  let calls = 0;
  const model = { async generate() { calls++; return { text: `合成摘要${calls}`, inputTokens: calls, outputTokens: 1 }; } };
  const first = fixture(model, store);
  await first.call('POST', '', input);
  for (const principal of [{ ownerId: 'other', deviceId: 'device' }, { ownerId: 'owner', deviceId: 'other' }]) {
    const other = fixture(model, store, principal);
    assert.equal((await other.call('GET', `/${requestId}`)).status, 404);
    assert.equal((await other.call('POST', `/${requestId}/withdraw`)).status, 404);
    const created = await (await other.call('POST', '', { ...input, fingerprint: 'b'.repeat(64) })).json();
    assert.equal(created.state, 'completed');
    await other.call('POST', `/${requestId}/withdraw`);
    const original = await (await first.call('GET', `/${requestId}`)).json();
    assert.equal(original.state, 'completed');
    assert.equal(original.summary, '合成摘要1');
    assert.deepEqual(original.usage, { inputTokens: 1, outputTokens: 1 });
  }
  assert.equal(calls, 3);
  assert.equal(store.rows.size, 3);
});

test('JSON字段顺序不改变规范输入hash或重发许可', async () => {
  let calls = 0;
  const f = fixture({ async generate() { calls++; return { text: '摘要', inputTokens: 1, outputTokens: 1 }; } });
  await f.call('POST', '', input);
  const reordered = { sources: input.sources.map(({ requestId, prompt, answer }) => ({ answer, prompt, requestId })),
    approved: true, fingerprint: input.fingerprint, requestId, version: input.version };
  assert.equal((await f.call('POST', '', reordered)).status, 200);
  assert.equal(calls, 1);
});

test('查询补记期间撤回完成，不交付旧摘要快照', async () => {
  const store = new Store();
  let calls = 0;
  let pause = false;
  let entered!: () => void;
  let release!: () => void;
  const ready = new Promise<void>((resolve) => { entered = resolve; });
  const resumed = new Promise<void>((resolve) => { release = resolve; });
  const handle = createSummaryService({ store,
    authenticate: async () => ({ ownerId: 'owner', deviceId: 'device' }),
    accounting: { begin: async () => true, async record() {
      if (pause) { pause = false; entered(); await resumed; }
    } },
    model: { async generate() { calls++; return { text: '不得交付的旧摘要', inputTokens: 3, outputTokens: 1 }; } },
  });
  const call = (method: string, suffix = '', body?: unknown) => handle(new Request(
    `https://agent.example.com/v1/agent/summaries${suffix}`, { method,
      headers: { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) }));
  await (await call('POST', '', input)).text();
  pause = true;
  const pending = call('GET', `/${requestId}`);
  await ready;
  const withdrawn = await (await call('POST', `/${requestId}/withdraw`)).json();
  assert.equal(withdrawn.state, 'withdrawn');
  release();
  const result = await (await pending).json();
  assert.equal(result.state, 'withdrawn');
  assert.equal(result.summary, null);
  assert.deepEqual(result.usage, { inputTokens: 3, outputTokens: 1 });
  assert.equal(calls, 1);
});

test('交付前权威读取失败、缺失或绑定改变均不回退旧正文', async () => {
  for (const mode of ['failure', 'missing', 'binding'] as const) {
    let calls = 0;
    const f = fixture({ async generate() {
      calls++; return { text: '不得泄露的旧正文', inputTokens: 3, outputTokens: 1 };
    } });
    await (await f.call('POST', '', input)).text();
    const read = f.store.read.bind(f.store);
    let reads = 0;
    f.store.read = async (principal, id) => {
      const row = await read(principal, id);
      if (++reads === 1) return row;
      if (mode === 'failure') throw new Error('合成最终读取失败');
      if (mode === 'missing') return null;
      return row && { ...row, inputHash: 'b'.repeat(64) };
    };
    const response = await f.call('GET', `/${requestId}`);
    assert.equal(response.status, 503, mode);
    assert.deepEqual(await response.json(), { error: 'unavailable' });
    assert.equal(reads, 2);
    assert.equal(calls, 1);
    f.store.read = read;
    assert.equal((await (await f.call('GET', `/${requestId}`)).json()).state, 'completed');
    assert.equal(calls, 1);
  }
});

test('非法JSON和错误HTTP方法拒绝且不调用模型', async () => {
  let calls = 0;
  const f = fixture({ async generate() { calls++; return { text: '摘要', inputTokens: 1, outputTokens: 1 }; } });
  const response = await f.handle(new Request('https://agent.example.com/v1/agent/summaries', { method: 'POST',
    headers: { Authorization: 'Bearer synthetic', 'Content-Type': 'application/json' }, body: '{' }));
  assert.equal(response.status, 400);
  assert.equal((await f.call('DELETE', `/${requestId}`)).status, 400);
  assert.equal(calls, 0); assert.equal(f.store.rows.size, 0);
});
