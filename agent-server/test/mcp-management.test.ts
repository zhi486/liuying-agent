import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { interceptD1 } from './d1-interceptor.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { mcpManagement } from '../src/mcp-management.ts';

const root = 'https://agent.invalid/v1/agent/mcp/services';
const endpoint = 'https://tools.example.com/mcp';
const signal = () => new AbortController().signal;
const catalog = () => collectMcpCatalog(async () => ({ tools: [{ name: 'read', inputSchema: { type: 'object' } }] }), signal());

test('损坏目录仍可读取受身份保护的元数据，旧版本不能移除新配置', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  const otherService = await f.store.register('https://other.example.com/mcp');
  f.sqlite.prepare('UPDATE agent_mcp_services SET catalog_json = ?, catalog_fingerprint = ? WHERE id = ?')
    .run('[]', '0'.repeat(64), service.id);
  await responseJson(await f.call(`/${service.id}`), 503);
  const summary = await responseJson(await f.call(`/${service.id}/summary`), 200);
  assert.deepEqual(summary, { service: { id: service.id, revision: service.revision, endpoint, enabled: false } });
  const newer = crypto.randomUUID();
  f.sqlite.prepare('UPDATE agent_mcp_services SET revision = ? WHERE id = ?').run(newer, service.id);
  await responseJson(await f.call(`/${service.id}/remove`, { revision: service.revision }), 409);
  await responseJson(await f.call(`/${service.id}/summary`, {}), 400);
  await responseJson(await f.call(`/${service.id}/summary?extra=1`), 400);
  await responseJson(await mcpManagement(f.request(`/${service.id}/summary`, undefined, 'invalid'), f.options), 401);
  const otherOwner = crypto.randomUUID();
  f.sqlite.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').run(otherOwner);
  const otherDevice = await createDeviceAdmin({ db: f.db }).issue(otherOwner);
  await responseJson(await mcpManagement(f.request(`/${service.id}/summary`, undefined, `Bearer ${otherDevice.token}`), f.options), 404);
  assert.deepEqual(await responseJson(await f.call(`/${service.id}/remove`, { revision: newer }), 200), { removed: true });
  assert.deepEqual(await f.store.get(otherService.id), otherService);
  await responseJson(await f.call(`/${service.id}/summary`), 404);
});
async function setup(t: { after: (run: () => void) => void }) {
  const now = Date.now;
  const database = openBudgetDatabase(':memory:', now); t.after(database.close);
  database.sqlite.exec(readFileSync(new URL('../migrations/0005_agent_mcp_services.sql', import.meta.url), 'utf8'));
  const ownerId = crypto.randomUUID();
  database.sqlite.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').run(ownerId);
  const d = await createDeviceAdmin({ db: database.db, now }).issue(ownerId);
  const principal = { ownerId, deviceId: d.deviceId };
  const header = `Bearer ${d.token}`;
  const options = { db: database.db, discover: async () => catalog() };
  const store = createMcpStore({ ...options, principal });
  const request = (path = '', body?: unknown, authorization = header) => new Request(root + path, {
    method: body === undefined ? 'GET' : 'POST', headers: { Authorization: authorization, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const call = (path = '', body?: unknown) => mcpManagement(request(path, body), options);
  return { ...database, principal, d, header, options, store, request, call };
}
async function responseJson(response: Response, status: number) {
  assert.equal(response.status, status);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.has('access-control-allow-origin'), false);
  return response.json() as Promise<Record<string, any>>;
}

test('管理 HTTP 登记默认关闭，列表、详情、换地址、停用和删除使用当前版本', async t => {
  const f = await setup(t);
  const { service: initial } = await responseJson(await f.call('', { endpoint }), 201);
  assert.equal(initial.enabled, false); assert.equal('ownerId' in initial, false);
  const list = await responseJson(await f.call(), 200);
  assert.equal(list.discoveryAvailable, true); assert.equal(list.nextCursor, null);
  assert.deepEqual(list.services.map((row: { id: string }) => row.id), [initial.id]);
  assert.deepEqual((await responseJson(await f.call(`/${initial.id}`), 200)).service, initial);
  const { service: changed } = await responseJson(await f.call(`/${initial.id}/endpoint`, {
    revision: initial.revision, endpoint: 'https://other.example.com/mcp' }), 200);
  assert.notEqual(changed.revision, initial.revision);
  const { service: disabled } = await responseJson(await f.call(`/${changed.id}/disable`, { revision: changed.revision }), 200);
  assert.deepEqual(await responseJson(await f.call(`/${changed.id}/remove`, { revision: disabled.revision }), 200), { removed: true });
  assert.deepEqual(await responseJson(await f.call(`/${initial.id}`), 404), { error: 'not_found' });
});

test('只有可信发现目录可供明确批准；旧页面和重复批准均为冲突', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  const { service: found } = await responseJson(await f.call(`/${service.id}/discover`, { revision: service.revision }), 200);
  assert.equal(found.enabled, false);
  const approval = { revision: found.revision, selection: [{ name: 'read', mode: 'read' }], confirmed: true };
  const { service: approved } = await responseJson(await f.call(`/${service.id}/approve`, approval), 200);
  assert.equal(approved.enabled, true);
  assert.deepEqual(await responseJson(await f.call(`/${service.id}/approve`, approval), 409), { error: 'conflict' });
  await responseJson(await f.call(`/${service.id}/disable`, { revision: approved.revision }), 200);
  await responseJson(await f.call(`/${service.id}/approve`, approval), 409);
});

test('未配置发现端口不联网、不清空现有批准，列表明确不可发现', async t => {
  const f = await setup(t); const initial = await f.store.register(endpoint);
  const found = await f.store.discover(initial.id, initial.revision, signal());
  const approved = await f.store.approve(initial.id, found.revision, [{ name: 'read', mode: 'confirm' }]);
  const options = { db: f.db };
  const list = await responseJson(await mcpManagement(f.request(), options), 200);
  assert.equal(list.discoveryAvailable, false);
  assert.deepEqual(await responseJson(await mcpManagement(f.request(`/${initial.id}/discover`, { revision: approved.revision }), options), 503),
    { error: 'unavailable' });
  assert.deepEqual(await f.store.get(initial.id), approved);
});

test('未认证请求不消费正文、不登记、不调用发现；撤销后旧令牌失效', async t => {
  const f = await setup(t); let calls = 0; let cancelled = false;
  const request = new Request(root, { method: 'POST', headers: { Authorization: 'invalid', 'Content-Type': 'application/json' },
    body: new ReadableStream({ cancel() { cancelled = true; } }), duplex: 'half' } as RequestInit);
  const options = { ...f.options, discover: async () => { calls++; return catalog(); } };
  assert.deepEqual(await responseJson(await mcpManagement(request, options), 401), { error: 'unauthenticated' });
  assert.equal(cancelled, true); assert.equal(calls, 0);
  assert.equal(f.sqlite.prepare('SELECT COUNT(*) n FROM agent_mcp_services').get()!.n, 0);
  await createDeviceAdmin({ db: f.db }).revoke(f.principal.ownerId, f.principal.deviceId);
  await responseJson(await f.call(), 401);
});

test('有效跨 owner 不能读、修改、删除或触发他人目录发现', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  const ownerId = crypto.randomUUID();
  f.sqlite.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').run(ownerId);
  const other = await createDeviceAdmin({ db: f.db }).issue(ownerId);
  const request = (path: string, body?: unknown) => f.request(path, body, `Bearer ${other.token}`);
  let calls = 0;
  const options = { ...f.options, discover: async () => { calls++; return catalog(); } };
  for (const action of ['disable', 'endpoint', 'discover']) {
    const body = { revision: service.revision, ...(action === 'endpoint' ? { endpoint } : {}) };
    await responseJson(await mcpManagement(request(`/${service.id}/${action}`, body), options), 404);
  }
  await responseJson(await mcpManagement(request(`/${service.id}/remove`, { revision: service.revision }), options), 409);
  await responseJson(await mcpManagement(request(`/${service.id}`), options), 404);
  assert.deepEqual((await responseJson(await mcpManagement(request(''), options), 200)).services, []);
  assert.equal(calls, 0); assert.deepEqual(await f.store.get(service.id), service);
});

test('拒绝自报身份、凭据、目录、批准状态和未知字段', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  for (const extra of [{ ownerId: f.principal.ownerId }, { deviceId: f.principal.deviceId }, { token: 'private' },
    { authorization: 'private' }, { catalog: [] }, { enabled: true }])
    await responseJson(await f.call('', { endpoint, ...extra }), 400);
  for (const extra of [{ catalog: [] }, { fingerprint: '0'.repeat(64) }, { endpoint }])
    await responseJson(await f.call(`/${service.id}/discover`, { revision: service.revision, ...extra }), 400);
  assert.deepEqual(await f.store.get(service.id), service);
});

test('批准需要明确 true、当前 revision、合法且不重复的工具模式', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  const found = await f.store.discover(service.id, service.revision, signal());
  const valid = { revision: found.revision, confirmed: true, selection: [{ name: 'read', mode: 'read' }] };
  for (const patch of [{ confirmed: false }, { confirmed: 'true' }, { confirmed: undefined }, { revision: '' },
    { selection: [] }, { selection: [{ name: 'read', mode: 'admin' }] }, { selection: [{ name: 'other', mode: 'read' }] },
    { selection: [valid.selection[0], valid.selection[0]] }, { selection: [{ ...valid.selection[0], modelName: 'forged' }] }])
    await responseJson(await f.call(`/${service.id}/approve`, { ...valid, ...patch }), 400);
  assert.equal((await f.store.get(service.id)).enabled, false);
});

test('拒绝错误路径、方法、明文传输、编码路径、额外查询和非法游标', async t => {
  const f = await setup(t);
  for (const path of ['/', '/not-uuid', `/${crypto.randomUUID()}/execute`, '?token=private', '?after=', '?after=x', '?after=x&after=y']) {
    const response = await f.call(path);
    assert.ok([400, 404].includes(response.status));
    assert.equal(response.headers.get('cache-control'), 'no-store');
  }
  for (const request of [new Request(root, { method: 'PUT', headers: { Authorization: f.header } }),
    new Request(root.replace('https:', 'http:'), { headers: { Authorization: f.header } }),
    new Request(root.replace('/services', '/%73ervices'), { headers: { Authorization: f.header } })])
    assert.ok([400, 404].includes((await mcpManagement(request, f.options)).status));
});

test('正文实际字节限流，严格 JSON／UTF-8／媒体类型，断开取消并释放 reader', async t => {
  const f = await setup(t);
  for (const headers of [{ 'Content-Type': 'text/plain' }, { 'Content-Type': 'application/json; charset=gbk' },
    { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip' }])
    await responseJson(await mcpManagement(new Request(root, { method: 'POST', headers: { ...headers, Authorization: f.header }, body: '{}' }), f.options), 415);
  for (const body of ['private-not-json', new Uint8Array([0x22, 0xc0, 0x80, 0x22])])
    await responseJson(await mcpManagement(new Request(root, { method: 'POST', headers: { Authorization: f.header, 'Content-Type': 'application/json' }, body }), f.options), 400);
  const body = JSON.stringify({ endpoint });
  const padded = body + ' '.repeat(16384 - Buffer.byteLength(body));
  await responseJson(await mcpManagement(new Request(root, { method: 'POST', headers: { Authorization: f.header, 'Content-Type': 'application/json' }, body: padded }), f.options), 201);
  await responseJson(await mcpManagement(new Request(root, { method: 'POST', headers: { Authorization: f.header, 'Content-Type': 'application/json', 'Content-Length': '1' }, body: padded + ' ' }), f.options), 413);
  const controller = new AbortController(); let cancelled = false;
  const request = new Request(root, { method: 'POST', headers: { Authorization: f.header, 'Content-Type': 'application/json' },
    signal: controller.signal, body: new ReadableStream({ cancel() { cancelled = true; } }), duplex: 'half' } as RequestInit);
  const pending = mcpManagement(request, f.options); controller.abort();
  await responseJson(await pending, 400);
  assert.equal(cancelled, true); assert.equal(request.body?.locked, false);
});

test('列表合法分页且响应不带 owner、秘密或完整目录', async t => {
  const f = await setup(t);
  for (let i = 0; i < 21; i++) await f.store.register(endpoint);
  const first = await responseJson(await f.call(), 200);
  const last = await responseJson(await f.call(`?after=${first.nextCursor}`), 200);
  assert.equal(first.services.length, 20); assert.equal(last.services.length, 1); assert.equal(last.nextCursor, null);
  assert.equal('ownerId' in first.services[0], false); assert.equal('catalog' in first.services[0], false);
  assert.equal(JSON.stringify(first).includes(f.d.token), false);
});

test('存储与发现故障只给固定分类，不回显异常、SQL 或上游正文', async t => {
  const f = await setup(t); const service = await f.store.register(endpoint);
  const db = interceptD1(f.db, async (sql, phase) => {
    if (phase === 'before' && sql.startsWith('UPDATE agent_mcp_services')) throw new Error('private-database-exception');
  });
  assert.deepEqual(await responseJson(await mcpManagement(f.request(`/${service.id}/disable`, { revision: service.revision }), { ...f.options, db }), 503),
    { error: 'unavailable' });
  assert.deepEqual(await responseJson(await mcpManagement(f.request(`/${service.id}/discover`, { revision: service.revision }), {
    ...f.options, discover: async () => { throw new Error('private-upstream-body'); } }), 503), { error: 'unavailable' });
});
