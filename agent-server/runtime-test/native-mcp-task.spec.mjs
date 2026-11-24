import { env, RpcTarget } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext, evictDurableObject } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const definition = { name: 'notes.add', inputSchema: { type: 'object', properties: { text: { type: 'string' } },
  required: ['text'], additionalProperties: false } };
const argumentsJson = '{"text":"合成便签，不是真实用户内容"}';
async function device(owner = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(owner).run();
  const issued = await createDeviceAdmin({ db: env.DB }).issue(owner);
  return { owner, deviceId: issued.deviceId, header: `Bearer ${issued.token}` };
}
async function fixture(mode = 'read') {
  const d = await device();
  const principal = { ownerId: d.owner, deviceId: d.deviceId };
  const store = createMcpStore({ db: env.DB, principal,
    discover: async () => collectMcpCatalog(async () => ({ tools: [definition] }), new AbortController().signal) });
  const registered = await store.register('https://tools.example.com/mcp');
  const found = await store.discover(registered.id, registered.revision, new AbortController().signal);
  const service = await store.approve(found.id, found.revision, [{ name: definition.name, mode }]);
  const body = { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(), prompt: '调用已选择的合成工具',
    consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
    conversation: { version: 'conversation_v1', id: crypto.randomUUID(), approved: true, messages: [] },
    mcpTools: { version: 'native_mcp_v1', services: [{ serviceId: service.id, revision: service.revision, generation: crypto.randomUUID() }] } };
  const requests = [];
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    // 正式服务只允许模型经过此模拟端口；任何插件直连都使测试失败。
    expect(String(url)).toMatch(/^https:\/\/api\.deepseek\.com\//);
    requests.push(JSON.parse(init.body));
    return Response.json({ choices: [{ index: 0, finish_reason: requests.length === 1 ? 'tool_calls' : 'stop', message: {
      role: 'assistant', content: requests.length === 1 ? null : '合成回答',
      ...(requests.length === 1 ? { tool_calls: [{ id: 'plugin_1', type: 'function', function: { name: 'mcp_call',
        arguments: JSON.stringify({ modelName: service.grants[0].modelName, argumentsJson }) } }] } : {}),
    } }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
  }));
  return { ...d, store, service, body, requests, principal };
}
async function http(d, taskId, action, data) {
  const path = `https://local.invalid/v1/agent/tasks/${taskId}/mcp${action ? '/' + action : ''}`;
  return worker.fetch(new Request(path, { method: action ? 'POST' : 'GET', headers: {
    Authorization: d.header, 'Content-Type': 'application/json',
  }, ...(data === undefined ? {} : { body: JSON.stringify(data) }) }), env, createExecutionContext());
}
async function start(f) {
  const ctx = createExecutionContext();
  const response = await worker.fetch(new Request('https://local.invalid/v1/agent/tasks', { method: 'POST', headers: {
    Authorization: f.header, 'Content-Type': 'application/json',
  }, body: JSON.stringify(f.body) }), env, ctx);
  expect(response.status).toBe(202);
  return { ctx, task: (await response.json()).task };
}
async function poll(f, task, type) {
  for (let i = 0; i < 150; i++) {
    const response = await http(f, task.id, type === 'delivery' ? 'take' : '');
    expect(response.status).toBe(200);
    const value = (await response.json())[type];
    if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error('未出现合成插件事件');
}
function receipt(delivery) {
  const packet = delivery.exchange;
  const rpc = packet.body ? JSON.parse(packet.body) : null;
  const status = packet.method === 'DELETE' ? 204 : rpc.method.startsWith('notifications/') ? 202 : 200;
  const result = rpc?.method === 'initialize'
    ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
    : rpc?.method === 'tools/list' ? { tools: [definition] } : { content: [{ type: 'text', text: '手机合成结果' }] };
  return { version: 'native_mcp_v1', generation: packet.generation, exchangeId: packet.exchangeId,
    status, text: status === 200 ? JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }) : '', sessionId: 'synthetic-session' };
}
const decision = (confirmation, value = 'approve') => ({ version: 'native_mcp_v1', invocationId: confirmation.invocationId,
  intentDigest: confirmation.intentDigest, generation: confirmation.generation, decision: value });
async function pump(f, task, first) {
  const packets = []; let next = first;
  while (true) {
    const delivery = next ?? await poll(f, task, 'delivery'); next = undefined;
    const { exchange, intent } = delivery;
    expect(intent.argumentsJson).toBe(argumentsJson);
    expect(exchange.serviceId).toBe(f.service.id);
    expect(exchange.requestId).toBe(f.body.clientRequestId);
    expect(exchange.taskId).toBe(task.id);
    expect(exchange.generation).toBe(f.body.mcpTools.services[0].generation);
    packets.push(exchange.body ? JSON.parse(exchange.body).method : exchange.method);
    const response = await http(f, task.id, 'reply', receipt(delivery));
    expect(response.status).toBe(200);
    if (exchange.method === 'DELETE') return packets;
  }
}
async function cancel(f, task, ctx) {
  await createCloudTasks({ db: env.DB }).cancel(f.header, task.id);
  await env.AGENT_RUNTIME.getByName(f.owner).interrupt(f.header, task.id, 'cancel');
  await waitOnExecutionContext(ctx);
}

it('正式 HTTP→DO→模型自主选工具→原手机取件及回执→模型最终回答，不走云端插件 fetch', async () => {
  const f = await fixture(); const { ctx, task } = await start(f);
  try {
    const first = await poll(f, task, 'delivery');
    expect(await (await http(f, task.id, 'take')).json()).toEqual({ delivery: null });
    expect(await (await http(f, task.id, '')).json()).toEqual({ confirmation: null });
    const methods = await pump(f, task, first);
    await waitOnExecutionContext(ctx);
    expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'tools/call', 'DELETE']);
    expect(f.requests).toHaveLength(2);
    expect(JSON.parse(f.requests[1].messages.at(-1).content).result.content[0].text).toBe('手机合成结果');
    const stored = await createCloudTasks({ db: env.DB }).get(f.header, task.id);
    expect(stored.status).toBe('completed');
    expect(stored.result.actions).toContainEqual({ kind: 'tool_completed', step: 1, tool: 'mcp.call' });
    expect((await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).first()).status).toBe('completed');
    class Sink extends RpcTarget { async accepted() {} }
    await evictDurableObject(env.AGENT_RUNTIME.getByName(f.owner));
    expect((await env.AGENT_RUNTIME.getByName(f.owner).execute(f.header, f.body, new Sink())).task.id).toBe(task.id);
    expect((await http(f, task.id, 'take')).status).toBe(409);
    expect(f.requests).toHaveLength(2);
  } finally { await cancel(f, task, ctx); }
});

it.each(['approve', 'deny'])('逐次确认展示实际参数，%s 之前没有任何 SDK 交付', async value => {
  const f = await fixture('confirm'); const { ctx, task } = await start(f);
  try {
    const confirmation = await poll(f, task, 'confirmation');
    expect(confirmation.name).toBe(definition.name);
    expect(confirmation.argumentsJson).toBe(argumentsJson);
    expect(await (await http(f, task.id, 'take')).json()).toEqual({ delivery: null });
    expect((await http(f, task.id, 'decision', { ...decision(confirmation), generation: crypto.randomUUID() })).status).toBe(409);
    expect((await http(f, task.id, 'decision', decision(confirmation, value))).status).toBe(200);
    if (value === 'approve') expect((await pump(f, task)).filter(row => row === 'tools/call')).toHaveLength(1);
    await waitOnExecutionContext(ctx);
    expect(f.requests).toHaveLength(2);
    if (value === 'deny') expect(JSON.parse(f.requests[1].messages.at(-1).content)).toEqual({ status: 'rejected', reason: 'declined' });
  } finally { await cancel(f, task, ctx); }
});

it('手机多轮交换累计超过十秒仍完成一次已确认调用，不延长原任务', async () => {
  const f = await fixture('confirm'); const { ctx, task } = await start(f);
  try {
    const confirmation = await poll(f, task, 'confirmation');
    expect((await http(f, task.id, 'decision', decision(confirmation))).status).toBe(200);
    const methods = [];
    for (let i = 0; i < 5; i++) {
      const delivery = await poll(f, task, 'delivery');
      methods.push(delivery.exchange.body ? JSON.parse(delivery.exchange.body).method : delivery.exchange.method);
      await new Promise(resolve => setTimeout(resolve, 2300));
      expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(200);
    }
    await waitOnExecutionContext(ctx);
    expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'tools/call', 'DELETE']);
    const stored = await createCloudTasks({ db: env.DB }).get(f.header, task.id);
    expect(stored.status).toBe('completed');
    expect(stored.deadlineAtMillis).toBe(task.deadlineAtMillis);
    expect(f.requests).toHaveLength(2);
    expect(JSON.parse(f.requests[1].messages.at(-1).content).result.content[0].text).toBe('手机合成结果');
    const rows = await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).all();
    expect(rows.results.map(row => row.status)).toEqual(['completed']);
  } finally { await cancel(f, task, ctx); }
});

it('同 owner 其他手机／其他 owner 不能读参数、取件、确认、回执或关闭原任务', async () => {
  const f = await fixture('confirm'); const sibling = await device(f.owner); const stranger = await device();
  const { ctx, task } = await start(f);
  try {
    const confirmation = await poll(f, task, 'confirmation');
    for (const [d, status] of [[sibling, 401], [stranger, 409]]) {
      for (const [action, data] of [['', undefined], ['take', undefined], ['decision', decision(confirmation)], ['close', undefined]]) {
        const response = await http(d, task.id, action, data);
        expect(response.status).toBe(status);
        expect(JSON.stringify(await response.json())).not.toContain(argumentsJson);
      }
    }
    expect((await http(f, task.id, 'decision', decision(confirmation, 'deny'))).status).toBe(200);
    await waitOnExecutionContext(ctx);
    expect(f.requests).toHaveLength(2);
  } finally { await cancel(f, task, ctx); }
});

it.each(['cancel', 'disable', 'close'])('握手回执前 %s，不能继续交付，迟到回执不能复活插件调用', async action => {
  const f = await fixture(); const { ctx, task } = await start(f);
  try {
    const delivery = await poll(f, task, 'delivery');
    if (action === 'disable') await f.store.disable(f.service.id, f.service.revision);
    else if (action === 'close') expect((await http(f, task.id, 'close')).status).toBe(200);
    else await cancel(f, task, ctx);
    expect((await http(f, task.id, 'reply', receipt(delivery))).status).not.toBe(200);
    expect((await http(f, task.id, 'take')).status).not.toBe(200);
    if (action !== 'cancel') {
      // 关闭插件窗口仍允许模型用未知观察解释结果，不允许再次派发插件。
      await http(f, task.id, 'close');
      await waitOnExecutionContext(ctx);
      expect(f.requests).toHaveLength(2);
      expect(JSON.parse(f.requests[1].messages.at(-1).content)).toEqual({ status: 'unconfirmed', reason: 'unavailable' });
    } else expect(f.requests).toHaveLength(1);
    const row = await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).first();
    expect(row.status).toBe('approved');
  } finally { await cancel(f, task, ctx); }
});

it('tools/call 交付后丢回执并关闭窗口，账本只标记可能发送，不重发插件', async () => {
  const f = await fixture(); const { ctx, task } = await start(f);
  try {
    let delivery;
    for (let i = 0; i < 4; i++) {
      delivery = await poll(f, task, 'delivery');
      if (JSON.parse(delivery.exchange.body).method === 'tools/call') break;
      expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(200);
    }
    expect(JSON.parse(delivery.exchange.body).method).toBe('tools/call');
    expect(await (await http(f, task.id, 'take')).json()).toEqual({ delivery: null });
    expect((await http(f, task.id, 'close')).status).toBe(200);
    expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(409);
    await waitOnExecutionContext(ctx);
    expect(f.requests).toHaveLength(2);
    expect(JSON.parse(f.requests[1].messages.at(-1).content)).toEqual({ status: 'unconfirmed', reason: 'unavailable' });
    expect((await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).first()).status).toBe('may_have_dispatched');
  } finally { await cancel(f, task, ctx); }
});

it.each(['confirmation', 'delivery', 'tools/call'])('对话删除发生于 %s 时关闭插件内存通道，保留审计且拒绝迟到回执', async stage => {
  const f = await fixture(stage === 'confirmation' ? 'confirm' : 'read'); const { ctx, task } = await start(f);
  try {
    let confirmation; let delivery;
    if (stage === 'confirmation') confirmation = await poll(f, task, 'confirmation');
    else {
      delivery = await poll(f, task, 'delivery');
      if (stage === 'tools/call') {
        for (let i = 0; i < 4 && JSON.parse(delivery.exchange.body).method !== 'tools/call'; i++) {
          expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(200);
          delivery = await poll(f, task, 'delivery');
        }
        expect(JSON.parse(delivery.exchange.body).method).toBe('tools/call');
      }
    }
    const before = await env.DB.prepare('SELECT * FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).all();
    expect(before.results).toHaveLength(1);
    const response = await worker.fetch(new Request(`https://local.invalid/v1/agent/requests/${f.body.clientRequestId}/purge`, {
      method: 'POST', headers: { Authorization: f.header },
    }), env, createExecutionContext());
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ requestId: f.body.clientRequestId, purged: true });
    expect((await http(f, task.id, '')).status).toBe(409);
    expect((await http(f, task.id, 'take')).status).toBe(409);
    if (confirmation) expect((await http(f, task.id, 'decision', decision(confirmation))).status).toBe(409);
    if (delivery) expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(409);
    await waitOnExecutionContext(ctx);
    const after = await env.DB.prepare('SELECT * FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).all();
    expect(after.results).toEqual(before.results);
    expect((await createCloudTasks({ db: env.DB }).get(f.header, task.id)).result).toBeNull();
    expect(f.requests).toHaveLength(1);
  } finally { await cancel(f, task, ctx); }
});

it('提交中的服务版本已过期则保存明确未执行终态，不出网不调用模型也不等到超时', async () => {
  const f = await fixture();
  await f.store.disable(f.service.id, f.service.revision);
  const { ctx, task } = await start(f);
  await waitOnExecutionContext(ctx);
  const stored = await createCloudTasks({ db: env.DB }).get(f.header, task.id);
  expect(stored.status).toBe('blocked');
  expect(stored.result).toEqual({ status: 'blocked', reason: 'scope_denied', actions: [] });
  expect(f.requests).toHaveLength(0);
  expect((await env.DB.prepare('SELECT count(*) AS n FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).first()).n).toBe(0);
});

it('直接 RPC 同样校验参数；错误代际、意图或多余批准字段不能消费待确认', async () => {
  const f = await fixture('confirm'); const { ctx, task } = await start(f);
  const runtime = env.AGENT_RUNTIME.getByName(f.owner);
  try {
    const confirmation = await poll(f, task, 'confirmation');
    const valid = decision(confirmation);
    for (const [action, body, code] of [['claim', undefined, 'invalid_request'], ['take', {}, 'invalid_request'],
      ['decision', { ...valid, confirmed: true }, 'invalid_request'], ['decision', { ...valid, intentDigest: '0'.repeat(64) }, 'conflict']]) {
      expect(await runtime.mcpTask(f.header, task.id, action, body)).toEqual({ ok: false, error: code });
    }
    expect((await runtime.mcpTask(f.header, task.id, 'get')).data.confirmation).toEqual(confirmation);
    expect((await runtime.mcpTask(f.header, task.id, 'take')).data.delivery).toBeNull();
    expect((await http(f, task.id, 'decision', decision(confirmation, 'deny'))).status).toBe(200);
    await waitOnExecutionContext(ctx);
  } finally { await cancel(f, task, ctx); }
});

it('工具结果已返回但 SDK 尚未收尾时关闭窗口，迟到内容不能进入下一轮模型', async () => {
  const f = await fixture(); const { ctx, task } = await start(f);
  try {
    for (let i = 0; i < 4; i++) {
      const delivery = await poll(f, task, 'delivery');
      expect((await http(f, task.id, 'reply', receipt(delivery))).status).toBe(200);
    }
    const cleanup = await poll(f, task, 'delivery');
    expect(cleanup.exchange.method).toBe('DELETE');
    expect((await http(f, task.id, 'close')).status).toBe(200);
    expect((await http(f, task.id, 'reply', receipt(cleanup))).status).toBe(409);
    await waitOnExecutionContext(ctx);
    expect(f.requests).toHaveLength(2);
    expect(JSON.stringify(f.requests[1])).not.toContain('手机合成结果');
    expect(JSON.parse(f.requests[1].messages.at(-1).content)).toEqual({ status: 'unconfirmed', reason: 'unavailable' });
    expect((await env.DB.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(task.id).first()).status).toBe('may_have_dispatched');
  } finally { await cancel(f, task, ctx); }
});
