import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { collectMcpCatalog, authorizeMcpTool } from '../src/mcp-registry.ts';
import { createMcpStore } from '../src/mcp-store.ts';

const endpoint = 'https://tools.example.com/mcp';
const catalog = (description = '合成资料') => collectMcpCatalog(async () => ({ tools: [{ name: 'read', description,
  inputSchema: { type: 'object' }, annotations: { readOnlyHint: true } }] }), new AbortController().signal);
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; };
async function setup(t: { after: (run: () => void) => void }, now = Date.now) {
  const database = openBudgetDatabase(':memory:', now);
  t.after(database.close);
  database.sqlite.exec(readFileSync(new URL('../migrations/0005_agent_mcp_services.sql', import.meta.url), 'utf8'));
  const ownerId = crypto.randomUUID();
  database.sqlite.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').run(ownerId);
  const device = await createDeviceAdmin({ db: database.db, now }).issue(ownerId);
  const principal = { ownerId, deviceId: device.deviceId };
  const options = { db: database.db, principal, discover: async () => catalog() };
  return { ...database, principal, device, options, store: createMcpStore(options) };
}
async function enabled(store: ReturnType<typeof createMcpStore>) {
  const service = await store.register(endpoint);
  const found = await store.discover(service.id, service.revision, new AbortController().signal);
  return store.approve(found.id, found.revision, [{ name: 'read', mode: 'read' }]);
}

test('登记默认关闭；重建仓库读回目录、批准和命名空间；不保存凭据', async t => {
  const f = await setup(t);
  const registered = await f.store.register(endpoint);
  assert.equal(registered.enabled, false);
  assert.deepEqual((await f.store.list()).items.map(row => row.id), [registered.id]);
  const first = await enabled(f.store);
  const reopened = createMcpStore(f.options);
  const restored = await reopened.get(first.id);
  assert.deepEqual(restored, first);
  assert.equal(Object.isFrozen(restored.catalog!.tools[0]!.definition), true);
  assert.equal(authorizeMcpTool(restored, f.principal.ownerId, restored.revision, restored.grants[0]!.modelName).status, 'allowed_read');
  const columns = f.sqlite.prepare('PRAGMA table_info(agent_mcp_services)').all().map(row => row.name);
  assert.deepEqual(columns, ['id', 'owner_id', 'endpoint', 'revision', 'enabled', 'catalog_json', 'catalog_fingerprint', 'grants_json']);
  const rows = f.sqlite.prepare('SELECT * FROM agent_mcp_services').all();
  assert.equal(JSON.stringify(rows).includes(f.device.token), false);
});

test('异步发现与停用／换地址／删除交错，迟到目录不能覆盖新状态或复活删除', async t => {
  const f = await setup(t);
  for (const action of ['disable', 'replaceEndpoint', 'remove'] as const) {
    const service = await enabled(f.store);
    const gate = deferred<Awaited<ReturnType<typeof catalog>>>();
    const started = deferred<void>();
    const late = createMcpStore({ ...f.options, discover: async () => { started.resolve(); return gate.promise; } });
    const pending = late.discover(service.id, service.revision, new AbortController().signal);
    await started.promise;
    const next = action === 'replaceEndpoint' ? await f.store.replaceEndpoint(service.id, service.revision, 'https://other.example.com/mcp')
      : await f.store[action](service.id, service.revision);
    gate.resolve(await catalog('迟到目录'));
    await assert.rejects(pending, { code: 'stale_review' });
    if (action === 'remove') await assert.rejects(f.store.get(service.id), { code: 'unavailable' });
    else assert.deepEqual(await f.store.get(service.id), next);
  }
});

test('相同目录不误撤权；定义变化撤权；失败或取消发现清空旧批准', async t => {
  const f = await setup(t);
  const first = await enabled(f.store);
  assert.deepEqual(await f.store.discover(first.id, first.revision, new AbortController().signal), first);
  const changed = createMcpStore({ ...f.options, discover: async () => catalog('新说明') });
  const second = await changed.discover(first.id, first.revision, new AbortController().signal);
  assert.equal(second.enabled, false);
  await assert.rejects(f.store.approve(second.id, first.revision, [{ name: 'read', mode: 'read' }]), { code: 'stale_review' });
  const approved = await f.store.approve(second.id, second.revision, [{ name: 'read', mode: 'read' }]);
  const broken = createMcpStore({ ...f.options, discover: async () => { throw new Error('private-network-failure'); } });
  await assert.rejects(broken.discover(approved.id, approved.revision, new AbortController().signal),
    error => error instanceof Error && error.message === 'MCP 配置操作未完成' && 'code' in error && error.code === 'discovery_failed');
  const invalidated = await f.store.get(approved.id);
  assert.equal(invalidated.catalog, null);
  assert.deepEqual(invalidated.grants, []);
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(f.store.discover(invalidated.id, invalidated.revision, controller.signal), { code: 'discovery_failed' });
});

test('停用后迟到的发现失败或取消，不能清空更新后的目录版本', async t => {
  const f = await setup(t);
  for (const cancelled of [false, true]) {
    const service = await enabled(f.store);
    const started = deferred<void>(); const gate = deferred<void>(); const controller = new AbortController();
    const late = createMcpStore({ ...f.options, discover: async () => {
      started.resolve(); await gate.promise;
      if (!cancelled) throw new Error('private-late-failure');
      return catalog();
    } });
    const pending = late.discover(service.id, service.revision, controller.signal);
    await started.promise;
    const disabled = await f.store.disable(service.id, service.revision);
    if (cancelled) controller.abort();
    gate.resolve();
    await assert.rejects(pending, { code: 'stale_review' });
    assert.deepEqual(await f.store.get(service.id), disabled);
  }
});

test('写入时比较 revision；两个页面不能同时改变同一批准', async t => {
  const f = await setup(t);
  const service = await enabled(f.store);
  const results = await Promise.allSettled([
    f.store.approve(service.id, service.revision, [{ name: 'read', mode: 'confirm' }]),
    f.store.replaceEndpoint(service.id, service.revision, 'https://other.example.com/mcp'),
  ]);
  assert.equal(results.filter(row => row.status === 'fulfilled').length, 1);
  assert.equal(results.filter(row => row.status === 'rejected' && row.reason.code === 'stale_review').length, 1);
});

test('跨 owner 不能读、列、修改或删除；无效设备不能登记', async t => {
  const f = await setup(t);
  const service = await enabled(f.store);
  const other = createMcpStore({ ...f.options, principal: { ...f.principal, ownerId: crypto.randomUUID() } });
  assert.deepEqual((await other.list()).items, []);
  await assert.rejects(other.get(service.id), { code: 'unavailable' });
  await assert.rejects(other.disable(service.id, service.revision), { code: 'unavailable' });
  await assert.rejects(other.remove(service.id, service.revision), { code: 'stale_review' });
  await assert.rejects(other.register(endpoint), { code: 'unavailable' });
  assert.deepEqual(await f.store.get(service.id), service);
});

test('设备在 UPDATE 前被撤销或到期，既有页面的批准也不生效', async t => {
  let time = Date.now();
  const f = await setup(t, () => time);
  const service = await enabled(f.store);
  const db = interceptD1(f.db, async (sql, phase) => {
    if (phase === 'before' && sql.startsWith('UPDATE agent_mcp_services')) time = f.device.expiresAtMillis + 1;
  });
  const expired = createMcpStore({ ...f.options, db });
  await assert.rejects(expired.approve(service.id, service.revision, [{ name: 'read', mode: 'confirm' }]), { code: 'stale_review' });
  time = f.device.expiresAtMillis - 1;
  assert.deepEqual(await f.store.get(service.id), service);
  await createDeviceAdmin({ db: f.db, now: () => time }).revoke(f.principal.ownerId, f.principal.deviceId);
  await assert.rejects(f.store.get(service.id), { code: 'unavailable' });
  await assert.rejects(f.store.remove(service.id, service.revision), { code: 'stale_review' });
});

test('坏指纹、非法批准、命名空间或停用状态损坏均不开放工具，但仍能移除', async t => {
  const f = await setup(t);
  for (const patch of [
    { catalog_fingerprint: '0'.repeat(64) },
    { grants_json: JSON.stringify([{ name: 'read', mode: 'read', modelName: 'mcp_forged' }]) },
    { grants_json: JSON.stringify([{ name: 'read', mode: 'admin', modelName: 'mcp_forged' }]) },
    { enabled: 0 },
    { catalog_json: '{}' },
  ]) {
    const service = await enabled(f.store);
    for (const [column, value] of Object.entries(patch))
      f.sqlite.prepare(`UPDATE agent_mcp_services SET ${column} = ? WHERE id = ?`).run(value, service.id);
    await assert.rejects(f.store.get(service.id), { code: 'invalid_state' });
    await f.store.remove(service.id, service.revision);
  }
});

test('目录由可信传输提供也需验证，坏指纹不能写入批准快照', async t => {
  const f = await setup(t);
  for (const approved of [false, true]) {
    const service = approved ? await enabled(f.store) : await f.store.register(endpoint);
    const forged = createMcpStore({ ...f.options, discover: async () => ({ ...await catalog('错误的新目录'),
      fingerprint: service.catalog?.fingerprint ?? '0'.repeat(64) }) });
    await assert.rejects(forged.discover(service.id, service.revision, new AbortController().signal), { code: 'discovery_failed' });
    const current = await f.store.get(service.id);
    assert.equal(current.catalog, null);
    assert.equal(current.enabled, false);
    assert.deepEqual(current.grants, []);
    assert.notEqual(current.revision, service.revision);
  }
});

test('列表以 id 稳定分页、限制体积，不返回目录；游标不参与 SQL 拼接', async t => {
  const f = await setup(t);
  for (let i = 0; i < 23; i++) await f.store.register(endpoint);
  const first = await f.store.list();
  assert.equal(first.items.length, 20);
  assert.ok(first.nextCursor);
  assert.equal('catalog' in first.items[0]!, false);
  const last = await f.store.list(first.nextCursor);
  assert.equal(last.items.length, 3);
  assert.equal(last.nextCursor, null);
  assert.equal(new Set([...first.items, ...last.items].map(row => row.id)).size, 23);
  await assert.rejects(f.store.list("' OR 1=1 --"), { code: 'invalid_request' });
});

test('写入故障不宣称已批准，返回固定错误，不泄漏数据库异常', async t => {
  const f = await setup(t);
  const service = await enabled(f.store);
  const db = interceptD1(f.db, async (sql, phase) => {
    if (phase === 'before' && sql.startsWith('UPDATE agent_mcp_services')) throw new Error('private-db-error');
  });
  const broken = createMcpStore({ ...f.options, db });
  await assert.rejects(broken.disable(service.id, service.revision), error => error instanceof Error &&
    error.message === 'MCP 配置操作未完成' && 'code' in error && error.code === 'storage_failed');
  assert.deepEqual(await f.store.get(service.id), service);
});
