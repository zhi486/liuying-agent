import { env } from 'cloudflare:workers';
import { applyD1Migrations } from 'cloudflare:test';
import { beforeAll, expect, it } from 'vitest';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { authorizeMcpTool, collectMcpCatalog } from '../src/mcp-registry.ts';
import { createMcpStore } from '../src/mcp-store.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
const endpoint = 'https://tools.example.com/mcp';
const catalog = (description = '合成目录') => collectMcpCatalog(async () => ({ tools: [{ name: 'read', description,
  inputSchema: { type: 'object' } }] }), new AbortController().signal);
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function setup(ownerId = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
  const device = await createDeviceAdmin({ db: env.DB }).issue(ownerId);
  const principal = { ownerId, deviceId: device.deviceId };
  const options = { db: env.DB, principal, discover: async () => catalog() };
  return { device, principal, options, store: createMcpStore(options) };
}
async function enabled(store) {
  const service = await store.register(endpoint);
  const found = await store.discover(service.id, service.revision, new AbortController().signal);
  return store.approve(found.id, found.revision, [{ name: 'read', mode: 'read' }]);
}

it('实际 D1 迁移后默认关闭，重建仓库恢复批准且不保存设备秘密', async () => {
  const f = await setup();
  const initial = await f.store.register(endpoint);
  expect(initial.enabled).toBe(false);
  const service = await enabled(f.store);
  const reopened = createMcpStore(f.options);
  expect(await reopened.get(service.id)).toEqual(service);
  const restored = await reopened.get(service.id);
  expect(authorizeMcpTool(restored, f.principal.ownerId, restored.revision, restored.grants[0].modelName).status).toBe('allowed_read');
  const row = await env.DB.prepare('SELECT * FROM agent_mcp_services WHERE id = ?').bind(service.id).first();
  expect(JSON.stringify(row)).not.toContain(f.device.token);
  expect(Object.keys(row).sort()).toEqual(['id', 'owner_id', 'endpoint', 'revision', 'enabled', 'catalog_json', 'catalog_fingerprint', 'grants_json'].sort());
});

it('实际 D1 CAS 阻止迟到发现覆盖停用、换地址或删除', async () => {
  const f = await setup();
  for (const action of ['disable', 'replaceEndpoint', 'remove']) {
    const service = await enabled(f.store);
    const started = deferred(); const gate = deferred();
    const late = createMcpStore({ ...f.options, discover: async () => { started.resolve(); return gate.promise; } });
    const pending = late.discover(service.id, service.revision, new AbortController().signal);
    await started.promise;
    const next = action === 'replaceEndpoint' ? await f.store.replaceEndpoint(service.id, service.revision, 'https://other.example.com/mcp')
      : await f.store[action](service.id, service.revision);
    gate.resolve(await catalog('迟到结果'));
    await expect(pending).rejects.toMatchObject({ code: 'stale_review' });
    if (action === 'remove') await expect(f.store.get(service.id)).rejects.toMatchObject({ code: 'unavailable' });
    else expect(await f.store.get(service.id)).toEqual(next);
  }
});

it('实际 D1 并行批准仅一项生效，相同目录的条件更新不误报冲突', async () => {
  const f = await setup(); const service = await enabled(f.store);
  expect(await f.store.discover(service.id, service.revision, new AbortController().signal)).toEqual(service);
  const results = await Promise.allSettled([
    f.store.approve(service.id, service.revision, [{ name: 'read', mode: 'read' }]),
    f.store.approve(service.id, service.revision, [{ name: 'read', mode: 'confirm' }]),
  ]);
  expect(results.filter(row => row.status === 'fulfilled')).toHaveLength(1);
  expect(results.filter(row => row.status === 'rejected' && row.reason.code === 'stale_review')).toHaveLength(1);
});

it('有效但不同 owner 的设备不能越权；同 owner 被撤销的设备不能提交迟到批准', async () => {
  const f = await setup(); const other = await setup(); const sibling = await setup(f.principal.ownerId);
  const service = await enabled(f.store);
  expect((await other.store.list()).items).toEqual([]);
  await expect(other.store.get(service.id)).rejects.toMatchObject({ code: 'unavailable' });
  await expect(other.store.remove(service.id, service.revision)).rejects.toMatchObject({ code: 'stale_review' });
  expect(await sibling.store.get(service.id)).toEqual(service);
  const started = deferred(); const gate = deferred();
  const late = createMcpStore({ ...f.options, discover: async () => { started.resolve(); return gate.promise; } });
  const pending = late.discover(service.id, service.revision, new AbortController().signal);
  await started.promise;
  await createDeviceAdmin({ db: env.DB }).revoke(f.principal.ownerId, f.principal.deviceId);
  gate.resolve(await catalog('设备撤销后返回'));
  await expect(pending).rejects.toMatchObject({ code: 'stale_review' });
  await expect(f.store.register(endpoint)).rejects.toMatchObject({ code: 'unavailable' });
  expect(await sibling.store.get(service.id)).toEqual(service);
});

it('实际 D1 失败和错误目录均清空批准，损坏快照仍能按版本删除', async () => {
  const f = await setup(); const service = await enabled(f.store);
  const forged = createMcpStore({ ...f.options, discover: async () => ({ ...await catalog('伪造的新目录'), fingerprint: service.catalog.fingerprint }) });
  await expect(forged.discover(service.id, service.revision, new AbortController().signal)).rejects.toMatchObject({ code: 'discovery_failed' });
  const invalidated = await f.store.get(service.id);
  expect(invalidated.catalog).toBe(null);
  expect(invalidated.grants).toEqual([]);
  const current = await enabled(f.store);
  await env.DB.prepare('UPDATE agent_mcp_services SET catalog_fingerprint = ? WHERE id = ?').bind('0'.repeat(64), current.id).run();
  await expect(f.store.get(current.id)).rejects.toMatchObject({ code: 'invalid_state' });
  await f.store.remove(current.id, current.revision);
  await expect(f.store.get(current.id)).rejects.toMatchObject({ code: 'unavailable' });
});

it('实际 D1 分页只返回有界元数据，数据库到期设备无法再读取', async () => {
  const f = await setup();
  for (let i = 0; i < 23; i++) await f.store.register(endpoint);
  const first = await f.store.list(); const last = await f.store.list(first.nextCursor);
  expect(first.items).toHaveLength(20); expect(last.items).toHaveLength(3); expect(last.nextCursor).toBe(null);
  expect(new Set([...first.items, ...last.items].map(row => row.id)).size).toBe(23);
  expect(first.items[0]).not.toHaveProperty('catalog');
  // 保持设备原有九十天有效期约束，将整个合成有效区间移到过去。
  await env.DB.prepare('UPDATE agent_devices SET created_at = created_at - 7776001000, expires_at = expires_at - 7776001000 WHERE id = ?')
    .bind(f.principal.deviceId).run();
  expect((await f.store.list()).items).toEqual([]);
  await expect(f.store.get(first.items[0].id)).rejects.toMatchObject({ code: 'unavailable' });
});

it('坏目录的单条元数据不解码目录且不能绕过 owner、版本与设备撤销', async () => {
  const f = await setup(); const other = await setup(); const service = await enabled(f.store);
  await env.DB.prepare('UPDATE agent_mcp_services SET catalog_fingerprint = ? WHERE id = ?').bind('0'.repeat(64), service.id).run();
  await expect(f.store.get(service.id)).rejects.toMatchObject({ code: 'invalid_state' });
  expect(await f.store.summary(service.id)).toEqual({ id: service.id, endpoint, revision: service.revision, enabled: true });
  await expect(other.store.summary(service.id)).rejects.toMatchObject({ code: 'unavailable' });
  await expect(f.store.remove(service.id, crypto.randomUUID())).rejects.toMatchObject({ code: 'stale_review' });
  expect((await f.store.summary(service.id)).revision).toBe(service.revision);
  await createDeviceAdmin({ db: env.DB }).revoke(f.principal.ownerId, f.principal.deviceId);
  await expect(f.store.summary(service.id)).rejects.toMatchObject({ code: 'unavailable' });
});
