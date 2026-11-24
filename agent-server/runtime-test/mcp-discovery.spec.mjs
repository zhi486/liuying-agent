import { env } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext, evictDurableObject, runInDurableObject } from 'cloudflare:test';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createMcpStore } from '../src/mcp-store.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const root = 'https://local.invalid/v1/agent/mcp/discoveries';
const endpoint = 'https://tools.example.com/mcp';
const tools = [{ name: 'read', description: '合成工具', inputSchema: { type: 'object' } }];
const pause = () => new Promise(resolve => setTimeout(resolve, 10));
async function device(ownerId = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
  const d = await createDeviceAdmin({ db: env.DB }).issue(ownerId);
  const principal = { ownerId, deviceId: d.deviceId };
  const store = createMcpStore({ db: env.DB, principal, discover: async () => { throw new Error('不得旁路发现'); } });
  return { header: `Bearer ${d.token}`, principal, store };
}
async function fixture() {
  const d = await device(); const service = await d.store.register(endpoint);
  return { ...d, service, input: { requestId: crypto.randomUUID(), serviceId: service.id, revision: service.revision, generation: crypto.randomUUID() } };
}
async function http(d, path = '', body, method = body === undefined ? 'GET' : 'POST') {
  const ctx = createExecutionContext();
  const response = await worker.fetch(new Request(root + path, { method,
    headers: { Authorization: d.header, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), env, ctx);
  expect(response.headers.get('cache-control')).toBe('no-store');
  expect(response.headers.has('access-control-allow-origin')).toBe(false);
  return { response, ctx };
}
async function start(d) {
  const result = await http(d, '', d.input);
  expect(result.response.status).toBe(202);
  expect((await result.response.json()).discovery.status).toBe('running');
  return result.ctx;
}
async function take(d) {
  for (let i = 0; i < 150; i++) {
    const { response } = await http(d, `/${d.input.requestId}/take`, undefined, 'POST');
    expect(response.status).toBe(200);
    const { exchange } = await response.json();
    if (exchange) return exchange;
    await pause();
  }
  throw new Error('未收到合成手机请求');
}
const reply = packet => {
  const message = packet.body && JSON.parse(packet.body);
  const status = packet.method === 'DELETE' ? 204 : message.method === 'notifications/initialized' ? 202 : 200;
  const result = message?.method === 'initialize'
    ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
    : { tools };
  return { version: 'native_mcp_v1', exchangeId: packet.exchangeId, generation: packet.generation,
    status, text: status === 200 ? JSON.stringify({ jsonrpc: '2.0', id: message.id, result }) : '', sessionId: 'synthetic-session' };
};
async function deliver(d, packet, response = reply(packet)) {
  const { response: accepted } = await http(d, `/${d.input.requestId}/reply`, response);
  expect(accepted.status).toBe(200); expect(await accepted.json()).toEqual({ accepted: true });
}
async function complete(d) {
  const methods = [];
  for (let i = 0; i < 4; i++) {
    const packet = await take(d);
    expect(packet.requestId).toBe(d.input.requestId); expect(packet.taskId).toBe(d.input.requestId);
    expect(packet.serviceId).toBe(d.service.id); expect(packet.revision).toBe(d.input.revision);
    expect(packet.endpoint).toBe(endpoint);
    methods.push(packet.body ? JSON.parse(packet.body).method : packet.method);
    await deliver(d, packet);
    expect(['running', 'completed']).toContain((await status(d)).status);
  }
  expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'DELETE']);
}
async function status(d) {
  const { response } = await http(d, `/${d.input.requestId}`);
  expect(response.status).toBe(200); return (await response.json()).discovery;
}
async function stop(d, ctx) {
  const view = await status(d);
  if (view.status === 'running') {
    const current = await d.store.get(d.service.id);
    await d.store.disable(current.id, current.revision);
    await http(d, `/${d.input.requestId}/take`, undefined, 'POST');
  }
  await waitOnExecutionContext(ctx);
}

it('正式 HTTP→DO→SDK→手机信封→D1 保存目录，不创建聊天、模型或外网请求', async () => {
  const d = await fixture(); const network = vi.fn(() => { throw new Error('不得访问外网'); }); vi.stubGlobal('fetch', network);
  const ctx = await start(d);
  try {
    await complete(d); await waitOnExecutionContext(ctx);
    const view = await status(d); expect(view.status).toBe('completed');
    const found = await d.store.get(d.service.id);
    expect(view.resultRevision).toBe(found.revision); expect(found.enabled).toBe(false); expect(found.grants).toEqual([]);
    expect(found.catalog.tools.map(tool => tool.name)).toEqual(['read']);
    expect((await env.DB.prepare('SELECT COUNT(*) n FROM agent_cloud_tasks WHERE owner_id=?').bind(d.principal.ownerId).first()).n).toBe(0);
    expect(network).not.toHaveBeenCalled();
    const replay = await http(d, '', d.input); expect(replay.response.status).toBe(200);
    expect((await replay.response.json()).discovery).toEqual(view); await waitOnExecutionContext(replay.ctx);
  } finally { await stop(d, ctx); }
});

it('早回执重复不重新启动；交付只一次，重复回执幂等，变更请求指纹冲突', async () => {
  const d = await fixture(); const ctx = await start(d);
  try {
    const replay = await http(d, '', d.input); expect(replay.response.status).toBe(202); await waitOnExecutionContext(replay.ctx);
    const conflict = await http(d, '', { ...d.input, generation: crypto.randomUUID() }); expect(conflict.response.status).toBe(409);
    const packet = await take(d);
    const taken = await http(d, `/${d.input.requestId}/take`, undefined, 'POST'); expect(await taken.response.json()).toEqual({ exchange: null });
    await deliver(d, packet); await deliver(d, packet);
    expect((await http(d, `/${d.input.requestId}/reply`, { ...reply(packet), text: '{}' })).response.status).toBe(409);
    for (let i = 0; i < 3; i++) await deliver(d, await take(d));
    await waitOnExecutionContext(ctx); expect((await status(d)).status).toBe('completed');
  } finally { await stop(d, ctx); }
});

it('原手机独占：同 owner 异设备不可领取、回执或查询，跨 owner 不泄漏', async () => {
  const d = await fixture(); const sibling = await device(d.principal.ownerId); const stranger = await device();
  const ctx = await start(d);
  try {
    const packet = await take(d);
    for (const [actor, expected] of [[sibling, 401], [stranger, 404], [{ header: 'invalid' }, 401]]) {
      for (const [path, body, method] of [[`/${d.input.requestId}`, undefined, 'GET'],
        [`/${d.input.requestId}/take`, undefined, 'POST'], [`/${d.input.requestId}/reply`, reply(packet), 'POST']]) {
        const { response } = await http(actor, path, body, method); expect(response.status).toBe(expected);
        expect(JSON.stringify(await response.json())).not.toContain(endpoint);
      }
    }
    await deliver(d, packet);
    for (let i = 0; i < 3; i++) await deliver(d, await take(d));
    await waitOnExecutionContext(ctx);
  } finally { await stop(d, ctx); }
});

it('等待手机时更换服务版本，迟到成功不能覆盖新配置或再次交付', async () => {
  const d = await fixture(); const ctx = await start(d);
  try {
    const packet = await take(d);
    const changed = await d.store.replaceEndpoint(d.service.id, d.service.revision, 'https://changed.example.com/mcp');
    expect((await http(d, `/${d.input.requestId}/reply`, reply(packet))).response.status).toBe(409);
    await waitOnExecutionContext(ctx);
    expect((await status(d)).status).toBe('unconfirmed');
    expect(await d.store.get(d.service.id)).toEqual(changed);
    expect((await http(d, `/${d.input.requestId}/take`, undefined, 'POST')).response.status).toBe(409);
  } finally { await stop(d, ctx); }
});

it('手机发送前只读查询也重核服务版本，撤权后关闭通道而非继续返回 running', async () => {
  const d = await fixture(); const ctx = await start(d);
  try {
    await take(d);
    await d.store.disable(d.service.id, d.service.revision);
    expect((await http(d, `/${d.input.requestId}`)).response.status).toBe(409);
    await waitOnExecutionContext(ctx);
    expect((await status(d)).status).toBe('unconfirmed');
    expect((await http(d, `/${d.input.requestId}/take`, undefined, 'POST')).response.status).toBe(409);
  } finally { await stop(d, ctx); }
});

it('设备撤销后不再领取或回执；固定手机失败分类不回显上游秘密', async () => {
  const d = await fixture(); const ctx = await start(d);
  const packet = await take(d);
  await createDeviceAdmin({ db: env.DB }).revoke(d.principal.ownerId, d.principal.deviceId);
  expect((await http(d, `/${d.input.requestId}/reply`, reply(packet))).response.status).toBe(401);
  // 外层已拒绝请求不会唤醒 SDK；让原 10 秒总时限收尾，不伪造已撤销身份。
  await waitOnExecutionContext(ctx);
  const failed = await fixture(); const failedCtx = await start(failed);
  try {
    const next = await take(failed);
    await deliver(failed, next, { version: 'native_mcp_v1', exchangeId: next.exchangeId, generation: next.generation, error: 'authentication_required' });
    await waitOnExecutionContext(failedCtx);
    expect((await status(failed)).status).toBe('unconfirmed');
  } finally { await stop(failed, failedCtx); }
});

it('DO 重建保留完成标记；只有持久 running 而无内存通道时明确中断，不重启 SDK', async () => {
  const d = await fixture(); const ctx = await start(d);
  try {
    await complete(d); await waitOnExecutionContext(ctx);
    const original = await status(d); const runtime = env.AGENT_RUNTIME.getByName(d.principal.ownerId);
    await evictDurableObject(runtime); expect(await status(d)).toEqual(original);
    // 合成崩溃遗留标记；不将此测试描述成执行中的进程强杀。
    await runInDurableObject(runtime, async (_instance, state) => {
      state.storage.sql.exec('UPDATE runtime_mcp_discovery SET view_json=? WHERE request_id=?',
        JSON.stringify({ ...original, status: 'running', resultRevision: null, reason: null }), d.input.requestId);
    });
    await evictDurableObject(runtime);
    const replay = await http(d, '', d.input); expect(replay.response.status).toBe(200);
    const result = (await replay.response.json()).discovery; expect(result.status).toBe('unconfirmed'); expect(result.reason).toBe('interrupted');
    await waitOnExecutionContext(replay.ctx);
    expect((await http(d, `/${d.input.requestId}/take`, undefined, 'POST')).response.status).toBe(409);
  } finally { await stop(d, ctx); }
});

it('拒绝非空领取、未知字段、错误方法和未认证正文，不创建操作', async () => {
  const d = await fixture();
  for (const extra of [{ endpoint }, { ownerId: d.principal.ownerId }, { token: 'private' }, { catalog: tools }])
    expect((await http(d, '', { ...d.input, ...extra })).response.status).toBe(400);
  expect((await http(d, `/${d.input.requestId}/take`)).response.status).toBe(400);
  expect((await http(d, `/${d.input.requestId}/take`, {})).response.status).toBe(400);
  expect((await http({ header: 'invalid' }, '', d.input)).response.status).toBe(401);
  expect((await http(d, `/${d.input.requestId}`)).response.status).toBe(404);
});

it('重复扫描相同目录保留已有授权，目录变化则清空旧授权等待重新核对', async () => {
  const d = await fixture(); const ctx = await start(d);
  await complete(d); await waitOnExecutionContext(ctx);
  const found = await d.store.get(d.service.id);
  const approved = await d.store.approve(found.id, found.revision, [{ name: 'read', mode: 'confirm' }]);
  const again = { ...d, input: { ...d.input, requestId: crypto.randomUUID(), revision: approved.revision, generation: crypto.randomUUID() } };
  const againCtx = await start(again);
  try {
    await complete(again); await waitOnExecutionContext(againCtx);
    expect(await d.store.get(d.service.id)).toEqual(approved);
    const changed = { ...again, input: { ...again.input, requestId: crypto.randomUUID(), generation: crypto.randomUUID() } };
    const changedCtx = await start(changed);
    try {
      for (let i = 0; i < 4; i++) {
        const packet = await take(changed); const response = reply(packet);
        if (packet.body && JSON.parse(packet.body).method === 'tools/list') {
          const message = JSON.parse(response.text); message.result.tools[0].description = '合成变更目录'; response.text = JSON.stringify(message);
        }
        await deliver(changed, packet, response);
      }
      await waitOnExecutionContext(changedCtx);
      const latest = await d.store.get(d.service.id);
      expect(latest.revision).not.toBe(approved.revision); expect(latest.enabled).toBe(false); expect(latest.grants).toEqual([]);
    } finally { await stop(changed, changedCtx); }
  } finally { await stop(again, againCtx); }
});

it('SDK 目录翻页也走原手机通道，游标不成为新的任意网络入口', async () => {
  const d = await fixture(); const ctx = await start(d); let pages = 0;
  try {
    for (let i = 0; i < 5; i++) {
      const packet = await take(d); const response = reply(packet);
      const message = packet.body && JSON.parse(packet.body);
      if (message?.method === 'tools/list') {
        const output = JSON.parse(response.text); pages++;
        if (pages === 1) output.result.nextCursor = 'synthetic-page-2';
        else { expect(message.params.cursor).toBe('synthetic-page-2'); output.result.tools = [{ name: 'second', inputSchema: { type: 'object' } }]; }
        response.text = JSON.stringify(output);
      }
      await deliver(d, packet, response);
    }
    await waitOnExecutionContext(ctx); expect(pages).toBe(2);
    expect((await d.store.get(d.service.id)).catalog.tools.map(tool => tool.name)).toEqual(['read', 'second']);
  } finally { await stop(d, ctx); }
});

it('元数据槽满只拒绝新发现，不驱逐 24 小时内的去重标记；过期元数据可清理', async () => {
  const d = await fixture();
  await http(d, `/${d.input.requestId}`);
  const runtime = env.AGENT_RUNTIME.getByName(d.principal.ownerId);
  await runInDurableObject(runtime, async (_instance, state) => {
    const startedAtMillis = Date.now();
    for (let i = 0; i < 64; i++) {
      const requestId = crypto.randomUUID();
      const view = { ...d.input, requestId, version: 'mcp_discovery_v1', endpoint, startedAtMillis,
        deadlineAtMillis: startedAtMillis + 10000, status: 'unconfirmed', resultRevision: null, reason: 'interrupted' };
      state.storage.sql.exec('INSERT INTO runtime_mcp_discovery VALUES (?,?,?,?,?)', requestId, d.principal.deviceId, 'synthetic', startedAtMillis, JSON.stringify(view));
    }
  });
  expect((await http(d, '', d.input)).response.status).toBe(409);
  await runInDurableObject(runtime, async (_instance, state) => {
    expect(state.storage.sql.exec('SELECT COUNT(*) n FROM runtime_mcp_discovery').one().n).toBe(64);
    state.storage.sql.exec('UPDATE runtime_mcp_discovery SET created_at=?', Date.now() - 86400001);
  });
  const ctx = await start(d);
  try {
    await complete(d); await waitOnExecutionContext(ctx);
    await runInDurableObject(runtime, async (_instance, state) => {
      expect(state.storage.sql.exec('SELECT COUNT(*) n FROM runtime_mcp_discovery').one().n).toBe(1);
    });
  } finally { await stop(d, ctx); }
});
