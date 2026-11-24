import { env } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, listDurableObjectIds, waitOnExecutionContext } from 'cloudflare:test';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';
import { mcpManagement } from '../src/mcp-management.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const endpoint = 'https://tools.example.com/mcp';
const root = 'https://local.invalid/v1/agent/mcp/services';
const catalog = () => collectMcpCatalog(async () => ({ tools: [{ name: 'read', description: '合成工具', inputSchema: { type: 'object' } }] }), new AbortController().signal);
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function fixture() {
  const ownerId = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
  const device = await createDeviceAdmin({ db: env.DB }).issue(ownerId);
  const header = `Bearer ${device.token}`;
  const principal = { ownerId, deviceId: device.deviceId };
  const options = { db: env.DB, discover: async () => catalog() };
  return { header, principal, options, store: createMcpStore({ ...options, principal }) };
}
function request(d, path = '', body) {
  return new Request(root + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: d.header, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
}
async function http(d, path = '', body) {
  const ctx = createExecutionContext();
  const response = await worker.fetch(request(d, path, body), env, ctx);
  await waitOnExecutionContext(ctx);
  expect(response.headers.get('cache-control')).toBe('no-store');
  expect(response.headers.has('access-control-allow-origin')).toBe(false);
  return response;
}

it('正式 Worker 管理路由完成元数据生命周期，不创建聊天 DO 或请求外网', async () => {
  const d = await fixture(); const before = (await listDurableObjectIds(env.AGENT_RUNTIME)).length;
  const network = vi.fn(() => { throw new Error('不得请求外网'); }); vi.stubGlobal('fetch', network);
  const registered = await http(d, '', { endpoint }); expect(registered.status).toBe(201);
  const { service } = await registered.json(); expect(service.enabled).toBe(false); expect(service).not.toHaveProperty('ownerId');
  const list = await (await http(d)).json(); expect(list.services).toHaveLength(1); expect(list.discoveryAvailable).toBe(false);
  expect(list.phoneDiscoveryAvailable).toBe(true);
  expect((await (await http(d, `/${service.id}`)).json()).service).toEqual(service);
  const changed = await (await http(d, `/${service.id}/endpoint`, { revision: service.revision, endpoint: 'https://other.example.com/mcp' })).json();
  const disabled = await (await http(d, `/${service.id}/disable`, { revision: changed.service.revision })).json();
  expect((await http(d, `/${service.id}/remove`, { revision: disabled.service.revision })).status).toBe(200);
  expect((await http(d, `/${service.id}`)).status).toBe(404);
  expect(network).not.toHaveBeenCalled();
  expect((await listDurableObjectIds(env.AGENT_RUNTIME)).length).toBe(before);
  expect((await env.DB.prepare('SELECT COUNT(*) n FROM agent_cloud_tasks WHERE owner_id = ?').bind(d.principal.ownerId).first()).n).toBe(0);
});

it('正式发现入口默认不可用，不产生网络请求也不抹去已批准状态', async () => {
  const d = await fixture(); const initial = await d.store.register(endpoint);
  const found = await d.store.discover(initial.id, initial.revision, new AbortController().signal);
  const approved = await d.store.approve(found.id, found.revision, [{ name: 'read', mode: 'confirm' }]);
  const network = vi.fn(() => { throw new Error('不得请求外网'); }); vi.stubGlobal('fetch', network);
  const response = await http(d, `/${approved.id}/discover`, { revision: approved.revision });
  expect(response.status).toBe(503); expect(await response.json()).toEqual({ error: 'unavailable' });
  expect(await d.store.get(approved.id)).toEqual(approved); expect(network).not.toHaveBeenCalled();
});

it('合成发现端口只收到端点与 signal，批准须确认且不信任远端注解', async () => {
  const d = await fixture(); const service = await d.store.register(endpoint);
  const discover = vi.fn(async (...args) => { expect(args).toHaveLength(2); expect(args[0]).toBe(endpoint); expect(args[1]).toBeInstanceOf(AbortSignal); return catalog(); });
  const options = { db: env.DB, discover };
  const response = await mcpManagement(request(d, `/${service.id}/discover`, { revision: service.revision }), options);
  expect(response.status).toBe(200); const { service: found } = await response.json(); expect(found.enabled).toBe(false);
  const selection = [{ name: 'read', mode: 'confirm' }];
  const unconfirmed = await http(d, `/${found.id}/approve`, { revision: found.revision, selection, confirmed: false });
  expect(unconfirmed.status).toBe(400);
  const approved = await http(d, `/${found.id}/approve`, { revision: found.revision, selection, confirmed: true });
  expect(approved.status).toBe(200); expect((await approved.json()).service.grants[0].mode).toBe('confirm');
  expect((await http(d, `/${found.id}/approve`, { revision: found.revision, selection, confirmed: true })).status).toBe(409);
  expect(discover).toHaveBeenCalledTimes(1);
});

it('未认证或已撤销设备不能管理，有效跨 owner 也看不到他人配置', async () => {
  const d = await fixture(); const other = await fixture(); const service = await d.store.register(endpoint);
  expect((await http({ header: 'invalid' })).status).toBe(401);
  expect((await http(other, `/${service.id}`)).status).toBe(404);
  expect((await http(other, `/${service.id}/disable`, { revision: service.revision })).status).toBe(404);
  expect((await http(other, `/${service.id}/remove`, { revision: service.revision })).status).toBe(409);
  expect((await (await http(other)).json()).services).toEqual([]);
  await createDeviceAdmin({ db: env.DB }).revoke(d.principal.ownerId, d.principal.deviceId);
  expect((await http(d)).status).toBe(401);
  expect((await http(d, '', { endpoint })).status).toBe(401);
});

it('正式入口拒绝身份、秘密、自报目录和未知执行入口，不改变批准', async () => {
  const d = await fixture(); const service = await d.store.register(endpoint);
  for (const extra of [{ ownerId: d.principal.ownerId }, { token: 'private-token' }, { catalog: [] }, { enabled: true }])
    expect((await http(d, '', { endpoint, ...extra })).status).toBe(400);
  expect((await http(d, `/${service.id}/discover`, { revision: service.revision, catalog: [] })).status).toBe(400);
  expect((await http(d, `/${service.id}/execute`, { revision: service.revision })).status).toBe(404);
  expect((await http(d, '?after=x&after=y')).status).toBe(400);
  expect(await d.store.get(service.id)).toEqual(service);
});

it('workerd 读取实际字节限流、严格 UTF-8；平台不接受 GET 正文', async () => {
  const d = await fixture();
  const headers = { Authorization: d.header, 'Content-Type': 'application/json' };
  const body = JSON.stringify({ endpoint }); const padded = body + ' '.repeat(16384 - new TextEncoder().encode(body).length);
  expect((await mcpManagement(new Request(root, { method: 'POST', headers, body: padded }), d.options)).status).toBe(201);
  expect((await mcpManagement(new Request(root, { method: 'POST', headers: { ...headers, 'Content-Length': '1' }, body: padded + ' ' }), d.options)).status).toBe(413);
  expect((await mcpManagement(new Request(root, { method: 'POST', headers, body: new Uint8Array([0x22, 0xc0, 0x80, 0x22]) }), d.options)).status).toBe(400);
  expect(() => new Request(root, { method: 'GET', headers, body: 'private' })).toThrow('cannot have a body');
});

it('HTTP 发现等待期间停用，迟到结果返回冲突而不是重开批准', async () => {
  const d = await fixture(); const service = await d.store.register(endpoint); const gate = deferred(); const entered = deferred();
  const pending = mcpManagement(request(d, `/${service.id}/discover`, { revision: service.revision }), {
    db: env.DB, discover: async () => { entered.resolve(); return gate.promise; },
  });
  try {
    await entered.promise;
    const stopped = await http(d, `/${service.id}/disable`, { revision: service.revision }); expect(stopped.status).toBe(200);
    const { service: disabled } = await stopped.json();
    gate.resolve(await catalog());
    const response = await pending; expect(response.status).toBe(409); expect(await response.json()).toEqual({ error: 'conflict' });
    expect((await d.store.get(service.id)).revision).toBe(disabled.revision);
    expect((await d.store.get(service.id)).enabled).toBe(false);
  } finally { gate.resolve(await catalog()); await pending; }
});
