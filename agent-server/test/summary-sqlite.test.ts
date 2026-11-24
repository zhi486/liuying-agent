import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { SummarySqliteStore } from '../tools/summary-sqlite-store.ts';
import { createSummaryService, type SummaryRow } from '../src/summary-service.ts';

const principal = { ownerId: 'synthetic', deviceId: 'device' };
const id = '11111111-1111-4111-8111-111111111111';
const row: SummaryRow = { requestId: id, fingerprint: 'a'.repeat(64), inputHash: 'b'.repeat(64),
  state: 'attempted', remoteFinished: false, summary: null, usage: null };
function path() { return join(mkdtempSync(join(tmpdir(), 'liuying-summary-')), 'summary.sqlite'); }

test('生成器进入后强杀子进程，恢复重复提交仍不派发', { timeout: 30000 }, async () => {
  const file = path();
  const storageModule = new URL('../tools/summary-sqlite-store.ts', import.meta.url).href;
  const serviceModule = new URL('../src/summary-service.ts', import.meta.url).href;
  const input = { version: 'summary_v1', requestId: id, fingerprint: row.fingerprint, approved: true,
    sources: [{ requestId: '22222222-2222-4222-8222-222222222222', prompt: '合成问题', answer: '合成回答' }] };
  const child = spawn(process.execPath, ['--experimental-strip-types', '--input-type=module', '-e',
    `import { SummarySqliteStore } from ${JSON.stringify(storageModule)};
     import { createSummaryService } from ${JSON.stringify(serviceModule)};
     const store = new SummarySqliteStore(${JSON.stringify(file)});
     const handle = createSummaryService({ store, authenticate: async () => (${JSON.stringify(principal)}),
       model: { async generate() { process.send('generating');
         return new Promise(() => { setInterval(() => {}, 1000); }); } } });
     await handle(new Request('https://synthetic.example/v1/agent/summaries', { method: 'POST',
       headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(input))} }));`],
    { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
  let stderr = '';
  child.stderr!.on('data', (data) => { stderr += String(data); });
  const exited = once(child, 'exit');
  try {
    const ready = await Promise.race([once(child, 'message'), exited.then(() => { throw new Error(stderr || '子进程提前退出'); })]);
    assert.equal(ready[0], 'generating');
    assert.equal(child.kill('SIGKILL'), true);
    const [code, signal] = await exited;
    assert.ok(code !== 0 || signal !== null);
    const restored = new SummarySqliteStore(file);
    try {
      let calls = 0;
      const handle = createSummaryService({ store: restored, authenticate: async () => principal,
        model: { async generate() { calls++; return { text: '不得重发', inputTokens: 1, outputTokens: 1 }; } } });
      const response = await handle(new Request('https://synthetic.example/v1/agent/summaries', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }));
      assert.equal(response.status, 200);
      const result = await response.json();
      assert.equal(result.state, 'attempted');
      assert.equal(result.remoteFinished, false);
      assert.equal(result.summary, null);
      assert.equal(result.cost, 'uncertain');
      assert.equal(calls, 0);
    } finally { restored.close(); }
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    await exited;
  }
});

test('独立进程提交退出后恢复同一请求，不重新领取', async () => {
  const file = path();
  const module = new URL('../tools/summary-sqlite-store.ts', import.meta.url).href;
  const child = spawnSync(process.execPath, ['--experimental-strip-types', '--input-type=module', '-e',
    `import { SummarySqliteStore } from ${JSON.stringify(module)};
     const store = new SummarySqliteStore(${JSON.stringify(file)});
     await store.create(${JSON.stringify(principal)}, ${JSON.stringify(row)});
     store.close();`], { encoding: 'utf8', timeout: 30000 });
  assert.equal(child.error, undefined);
  assert.equal(child.status, 0, child.stderr);
  const restored = new SummarySqliteStore(file);
  try {
    assert.deepEqual(await restored.read(principal, id), row);
    assert.equal((await restored.create(principal, row)).created, false);
  } finally { restored.close(); }
});

test('SQLite实际拒绝插入时返回不可用且不派发生成器', async () => {
  const file = path();
  const store = new SummarySqliteStore(file);
  const raw = new DatabaseSync(file);
  try {
    raw.exec(`CREATE TRIGGER reject_summary BEFORE INSERT ON summary_requests
      BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END`);
    let calls = 0;
    const handle = createSummaryService({ store, authenticate: async () => principal,
      model: { async generate() { calls++; return { text: '摘要', inputTokens: 1, outputTokens: 1 }; } } });
    const result = await handle(new Request('https://synthetic.example/v1/agent/summaries', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: 'summary_v1', requestId: id, fingerprint: row.fingerprint, approved: true,
        sources: [{ requestId: '22222222-2222-4222-8222-222222222222', prompt: '合成', answer: '合成' }] }) }));
    assert.equal(result.status, 503);
    assert.equal(calls, 0);
    assert.equal(await store.read(principal, id), null);
  } finally { raw.close(); store.close(); }
});

test('真实SQLite落盘重开保留未知请求，双连接原子创建不覆盖', async () => {
  const file = path();
  const first = new SummarySqliteStore(file);
  const second = new SummarySqliteStore(file);
  try {
    const results = await Promise.all([first.create(principal, row), second.create(principal, row)]);
    assert.equal(results.filter((result) => result.created).length, 1);
    assert.equal(await second.read({ ...principal, deviceId: 'other' }, id), null);
  } finally { first.close(); second.close(); }
  const restored = new SummarySqliteStore(file);
  try {
    assert.deepEqual(await restored.read(principal, id), row);
    assert.equal((await restored.create(principal, row)).created, false);
    await restored.change(principal, id, (current) => ({ ...current!, state: 'withdrawn' }));
  } finally { restored.close(); }
  const final = new SummarySqliteStore(file);
  try { assert.equal((await final.read(principal, id))?.state, 'withdrawn'); } finally { final.close(); }
});

test('严格快照拒绝额外来源字段及绑定修改，失败回滚不改原记录', async () => {
  const store = new SummarySqliteStore(path());
  try {
    await assert.rejects(store.create(principal, { ...row, sources: ['不得持久化'] } as SummaryRow));
    assert.equal(await store.read(principal, id), null);
    await assert.rejects(store.create(principal, { ...row, usage: { inputTokens: 1, outputTokens: 1, prompt: '不得持久化' } } as SummaryRow));
    await assert.rejects(store.create(principal, { ...row, requestId: 'invalid' }));
    await store.create(principal, row);
    for (const update of [
      { ...row, fingerprint: 'c'.repeat(64) },
      { ...row, inputHash: 'c'.repeat(64) },
      { ...row, requestId: '22222222-2222-4222-8222-222222222222' },
    ]) {
      await assert.rejects(store.change(principal, id, () => update));
      assert.deepEqual(await store.read(principal, id), row);
    }
    await assert.rejects(store.change(principal, id, (current) => {
      (current as { inputHash: string }).inputHash = 'c'.repeat(64);
      return current!;
    }));
    assert.deepEqual(await store.read(principal, id), row);
  } finally { store.close(); }
});

test('写入校验失败回滚，损坏原记录拒绝替代', async () => {
  const file = path();
  const store = new SummarySqliteStore(file);
  try {
    await store.create(principal, row);
    await assert.rejects(store.change(principal, id, () => ({ ...row, state: 'completed' })));
    assert.deepEqual(await store.read(principal, id), row);
    const raw = new DatabaseSync(file);
    try { raw.prepare('UPDATE summary_requests SET snapshot = ?').run('{'); } finally { raw.close(); }
    await assert.rejects(store.create(principal, row));
    await assert.rejects(store.read(principal, id));
  } finally { store.close(); }
});

test('持久协议重开只查询原请求，撤回后迟到结果不恢复正文', async () => {
  const file = path();
  const store = new SummarySqliteStore(file);
  let release!: () => void;
  let started!: () => void;
  const ready = new Promise<void>((resolve) => { started = resolve; });
  let calls = 0;
  const model = { async generate() {
    calls++; started();
    await new Promise<void>((resolve) => { release = resolve; });
    return { text: '不得恢复正文', inputTokens: 3, outputTokens: 1 };
  } };
  const handler = (storage: SummarySqliteStore) => createSummaryService({ store: storage, model, authenticate: async () => principal });
  const call = (handle: ReturnType<typeof handler>, method: string, suffix = '', body?: unknown) => handle(
    new Request(`https://synthetic.example/v1/agent/summaries${suffix}`, { method,
      headers: { 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) }));
  const input = { version: 'summary_v1', requestId: id, fingerprint: row.fingerprint, approved: true,
    sources: [{ requestId: '22222222-2222-4222-8222-222222222222', prompt: '合成问题', answer: '合成回答' }] };
  const pending = call(handler(store), 'POST', '', input);
  await ready;
  const reopened = new SummarySqliteStore(file);
  try {
    const recovered = handler(reopened);
    assert.equal((await (await call(recovered, 'GET', `/${id}`)).json()).state, 'attempted');
    await call(recovered, 'POST', '', input);
    assert.equal(calls, 1);
    await call(recovered, 'POST', `/${id}/withdraw`);
    release();
    const result = await (await pending).json();
    assert.equal(result.state, 'withdrawn');
    assert.equal(result.summary, null);
    assert.deepEqual(result.usage, { inputTokens: 3, outputTokens: 1 });
  } finally { reopened.close(); store.close(); }
});
