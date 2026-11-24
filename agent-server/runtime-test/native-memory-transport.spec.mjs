import { env, RpcTarget } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext, evictDurableObject } from 'cloudflare:test';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const input = () => ({ clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(), prompt: '我喜欢夜跑',
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
  conversation: { version: 'conversation_v1', id: crypto.randomUUID(), approved: true, messages: [] },
  memoryTools: { version: 'native_memory_v1', generation: crypto.randomUUID() } });
async function device(owner = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const issued = await createDeviceAdmin({ db: env.DB }).issue(owner);
  return { owner, deviceId: issued.deviceId, header: `Bearer ${issued.token}` };
}
const call = { id: 'save_1', name: 'memory.upsert', args: { category: 'preference', evidence: '我喜欢夜跑', target: null } };
const saved = () => ({ status: 'saved', memory: { id: crypto.randomUUID(), version: 1, content: call.args.evidence }, replayed: false });
const reply = (body, observation) => ({ version: 'native_memory_v1', generation: body.memoryTools.generation, callId: call.id, observation });
async function http(header, path, body) {
  return worker.fetch(new Request(`https://local.invalid/v1/agent/${path}`, {
    method: body === undefined ? 'GET' : 'POST', headers: { Authorization: header, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), env, createExecutionContext());
}
async function poll(d, taskId) {
  for (let i = 0; i < 100; i++) {
    const response = await http(d.header, `tasks/${taskId}/memory`);
    expect(response.status).toBe(200);
    const { memory } = await response.json();
    if (memory) return memory;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('合成工具未出现');
}
function provider(finalGate, toolCall = call) {
  const requests = [];
  const finalEntered = deferred();
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    requests.push(JSON.parse(init.body));
    if (requests.length === 1) return Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls', message: {
      role: 'assistant', content: null, tool_calls: [{ id: toolCall.id, type: 'function',
        function: { name: 'memory_upsert', arguments: JSON.stringify(toolCall.args) } }],
    } }], usage: { prompt_tokens: 100, completion_tokens: 10, total_tokens: 110 } });
    finalEntered.resolve();
    if (finalGate) await finalGate.promise;
    return Response.json({ choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '合成最终回答' } }],
      usage: { prompt_tokens: 100, completion_tokens: 10, total_tokens: 110 } });
  }));
  return { requests, finalEntered };
}
async function start(d, body) {
  const ctx = createExecutionContext();
  const response = await worker.fetch(new Request('https://local.invalid/v1/agent/tasks', {
    method: 'POST', headers: { Authorization: d.header, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }), env, ctx);
  expect(response.status).toBe(202);
  return { ctx, task: (await response.json()).task };
}

it('事实范围从正式任务入口传至模型目录与手机通道，回执仍用原协议', async () => {
  const d = await device();
  const fact = { ...call, args: { ...call.args, category: 'fact', evidence: '我的主要编程语言是Kotlin' } };
  const body = { ...input(), prompt: fact.args.evidence,
    memoryTools: { version: 'native_memory_v2', generation: crypto.randomUUID(), factsAllowed: true } };
  const { requests } = provider(undefined, fact);
  const { ctx, task } = await start(d, body);
  try {
    const memory = await poll(d, task.id);
    expect(memory.call).toEqual(fact);
    expect(memory.version).toBe('native_memory_v1');
    const definition = requests[0].tools.find(tool => tool.function.name === 'memory_upsert');
    expect(definition.function.parameters.properties.category.enum).toEqual(['preference', 'goal', 'fact']);
    const observation = { ...saved(), memory: { id: crypto.randomUUID(), version: 1, content: fact.args.evidence } };
    expect((await http(d.header, `tasks/${task.id}/memory`, reply(body, observation))).status).toBe(200);
    await waitOnExecutionContext(ctx);
    expect((await createCloudTasks({ db: env.DB }).get(d.header, task.id)).status).toBe('completed');
    expect(requests).toHaveLength(2);
    expect(JSON.parse(requests[1].messages.at(-1).content)).toEqual(observation);
    class Sink extends RpcTarget { async accepted() {} }
    expect((await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header,
      { ...body, memoryTools: { version: 'native_memory_v1', generation: body.memoryTools.generation } }, new Sink())).error).toBe('conflict');
  } finally { await waitOnExecutionContext(ctx); }
});

it('旧范围的模型不能通过事实调用越权，不向手机派发或续跑模型', async () => {
  const d = await device(); const body = input();
  const fact = { ...call, args: { ...call.args, category: 'fact', evidence: '我的专业是软件工程' } };
  const { requests } = provider(undefined, fact);
  const { ctx, task } = await start(d, body);
  await waitOnExecutionContext(ctx);
  const stored = await createCloudTasks({ db: env.DB }).get(d.header, task.id);
  expect(stored.status).toBe('blocked');
  expect(stored.result.reason).toBe('scope_denied');
  expect(requests).toHaveLength(1);
  const definition = requests[0].tools.find(tool => tool.function.name === 'memory_upsert');
  expect(definition.function.parameters.properties.category.enum).toEqual(['preference', 'goal']);
  expect((await http(d.header, `tasks/${task.id}/memory`)).status).toBe(409);
});

it('正式 HTTP→DO→模型循环等待原手机回执；重复确认不重复调用', async () => {
  const d = await device(); const body = input(); const gate = deferred();
  const { requests, finalEntered } = provider(gate);
  const { ctx, task } = await start(d, body);
  try {
    const memory = await poll(d, task.id);
    expect(memory).toEqual({ version: 'native_memory_v1', requestId: body.clientRequestId, taskId: task.id,
      generation: body.memoryTools.generation, call });
    expect(requests).toHaveLength(1);
    const observation = saved();
    const post = reply(body, observation);
    expect((await http(d.header, `tasks/${task.id}/memory`, post)).status).toBe(200);
    await finalEntered.promise;
    const duplicate = await http(d.header, `tasks/${task.id}/memory`, post);
    expect(duplicate.headers.get('Cache-Control')).toBe('no-store');
    expect(await duplicate.json()).toEqual({ accepted: true });
    expect((await http(d.header, `tasks/${task.id}/memory`, reply(body, { status: 'rejected', reason: 'conflict' }))).status).toBe(409);
    expect(requests).toHaveLength(2);
    expect(JSON.parse(requests[1].messages.at(-1).content)).toEqual(observation);
    gate.resolve();
    await waitOnExecutionContext(ctx);
    const stored = await createCloudTasks({ db: env.DB }).get(d.header, task.id);
    expect(stored.status).toBe('completed');
    expect(stored.result.actions).toContainEqual({ kind: 'tool_completed', step: 1, tool: 'memory.upsert' });
    const stub = env.AGENT_RUNTIME.getByName(d.owner);
    class Sink extends RpcTarget { async accepted() {} }
    expect((await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header, body, new Sink())).task.id).toBe(task.id);
    expect((await http(d.header, `tasks/${task.id}/memory`, post)).status).toBe(409);
    expect(requests).toHaveLength(2);
    expect((await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header,
      { ...body, memoryTools: { ...body.memoryTools, generation: crypto.randomUUID() } }, new Sink())).error).toBe('conflict');
  } finally { gate.resolve(); await waitOnExecutionContext(ctx); }
});

it('同 owner 另一台手机、其他 owner 和未认证访问都不能提取或伪造记忆回执', async () => {
  const d = await device(); const sibling = await device(d.owner); const stranger = await device();
  const body = input(); const { requests } = provider();
  const { ctx, task } = await start(d, body);
  try {
    await poll(d, task.id);
    for (const [header, status] of [[sibling.header, 401], [stranger.header, 409], ['invalid', 401]]) {
      for (const data of [undefined, reply(body, saved())]) {
        const response = await http(header, `tasks/${task.id}/memory`, data);
        expect(response.status).toBe(status);
        expect(JSON.stringify(await response.json())).not.toContain(call.args.evidence);
      }
    }
    expect(requests).toHaveLength(1);
    expect((await poll(d, task.id)).call).toEqual(call);
    expect((await http(d.header, `tasks/${task.id}/memory`, reply(body, { status: 'rejected', reason: 'consent_required' }))).status).toBe(200);
    await waitOnExecutionContext(ctx);
    expect(JSON.parse(requests[1].messages.at(-1).content)).toEqual({ status: 'rejected', reason: 'consent_required' });
  } finally {
    await createCloudTasks({ db: env.DB }).cancel(d.header, task.id);
    await env.AGENT_RUNTIME.getByName(d.owner).interrupt(d.header, task.id, 'cancel');
    await waitOnExecutionContext(ctx);
  }
});

it.each(['cancel', 'withdraw', 'revoke'])('等待工具时 %s，迟到成功不能进入模型或复活结果', async action => {
  const d = await device(); const body = input(); const { requests } = provider();
  const { ctx, task } = await start(d, body);
  try {
    await poll(d, task.id);
    if (action === 'revoke') await createDeviceAdmin({ db: env.DB }).revoke(d.owner, d.deviceId);
    else await createCloudTasks({ db: env.DB })[action](d.header, task.id);
    for (const data of [undefined, reply(body, saved())]) {
      const response = await http(d.header, `tasks/${task.id}/memory`, data);
      expect(response.status).toBe(action === 'revoke' ? 401 : 409);
    }
    // 撤权后通知不能再认证；工具八秒等待结束后，原账本阻止再次调用模型。
    if (action !== 'revoke') await env.AGENT_RUNTIME.getByName(d.owner).interrupt(d.header, task.id, action);
    await waitOnExecutionContext(ctx);
    expect(requests).toHaveLength(1);
    const stored = await env.DB.prepare('SELECT status,result_json FROM agent_cloud_tasks WHERE id=?').bind(task.id).first();
    expect(stored.status).toBe('cancelled');
    expect(stored.result_json).toBeNull();
  } finally { await waitOnExecutionContext(ctx); }
}, 15000);

it('回执代际不匹配不消费调用；坏回执统一未知而不是保存成功', async () => {
  const d = await device(); const body = input(); const { requests } = provider();
  const { ctx, task } = await start(d, body);
  try {
    await poll(d, task.id);
    expect((await http(d.header, `tasks/${task.id}/memory`, { ...reply(body, saved()), generation: crypto.randomUUID() })).status).toBe(409);
    expect((await poll(d, task.id)).call.id).toBe(call.id);
    expect((await http(d.header, `tasks/${task.id}/memory`, reply(body, { status: 'saved', privateError: 'PRIVATE-ERROR' }))).status).toBe(200);
    await waitOnExecutionContext(ctx);
    expect(JSON.parse(requests[1].messages.at(-1).content)).toEqual({ status: 'unconfirmed', reason: 'invalid_response' });
    expect(JSON.stringify(requests)).not.toContain('PRIVATE-ERROR');
  } finally { await waitOnExecutionContext(ctx); }
});

it('直接 RPC 完成后优雅重建只恢复原任务，不重新派发记忆或模型', async () => {
  const d = await device(); const body = input(); const { requests } = provider();
  const accepted = deferred();
  class Sink extends RpcTarget { async accepted(task) { accepted.resolve(task); } }
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const work = stub.execute(d.header, body, new Sink());
  const task = await accepted.promise;
  try {
    let memory;
    for (let i = 0; i < 100; i++) {
      const reply = await stub.memory(d.header, task.id);
      expect(reply.ok).toBe(true);
      memory = reply.memory;
      if (memory) break;
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    expect(memory?.call).toEqual(call);
    expect((await stub.memory(d.header, task.id, reply(body, saved()))).ok).toBe(true);
    expect((await work).task.status).toBe('completed');
    await evictDurableObject(stub);
    const replacement = env.AGENT_RUNTIME.getByName(d.owner);
    expect((await replacement.execute(d.header, body, new Sink())).task.id).toBe(task.id);
    expect(await replacement.memory(d.header, task.id)).toEqual({ ok: false, error: 'inactive' });
    expect(requests).toHaveLength(2);
  } finally { await work; }
});
