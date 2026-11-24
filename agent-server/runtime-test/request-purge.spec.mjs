import { env, RpcTarget } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext, runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { TaskEventStore } from '../src/task-event-store.ts';
import { interceptD1 } from '../test/d1-interceptor.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const input = () => ({ clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(), prompt: '合成删除测试',
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [] });
const tasks = () => createCloudTasks({ db: env.DB });
async function device(owner = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const issued = await createDeviceAdmin({ db: env.DB }).issue(owner);
  return { owner, deviceId: issued.deviceId, header: `Bearer ${issued.token}` };
}
const request = (d, path, method = 'GET', body, streaming = false) => new Request(`https://local.invalid/v1/agent/${path}`, {
  method, headers: { Authorization: d.header, 'Content-Type': 'application/json', ...(streaming ? { 'X-Agent-Stream': 'task_events_v1' } : {}) },
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});
const purge = (d, body, bindings = env) => worker.fetch(request(d, `requests/${body.clientRequestId}/purge`, 'POST'), bindings, createExecutionContext());
class Sink extends RpcTarget { async accepted() {} }
async function until(read, predicate) {
  for (let i = 0; i < 150; i++) {
    const value = await read();
    if (predicate(value)) return value;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error('合成状态未在预期内到达');
}
async function storedEvents(stub, taskId) {
  return runInDurableObject(stub, (_instance, state) => ({
    events: state.storage.sql.exec('SELECT * FROM runtime_task_events WHERE task_id=?', taskId).toArray(),
    streams: state.storage.sql.exec('SELECT * FROM runtime_task_streams WHERE task_id=?', taskId).toArray(),
  }));
}
async function completed(d, body) {
  const { task } = await tasks().accept(d.header, body);
  const execution = await tasks().claim(d.header, task.id);
  await tasks().finish(execution, { status: 'completed', answer: '合成 D1 正文', actions: [] });
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await runInDurableObject(stub, async (_instance, state) => {
    const store = new TaskEventStore(state.storage); store.start(task.id, d.deviceId);
    const signal = new AbortController().signal; const port = store.port(task.id, () => {});
    port.begin(`${task.id}:1`, signal); await port.text(`${task.id}:1`, '合成 DO 正文', signal);
    port.end(`${task.id}:1`, 'answer', signal); store.finish({ id: task.id, status: 'completed' });
  });
  return { task, stub };
}

it('未知请求清理通过正式 HTTP 持久化拒绝键；重复清理、DO 逐出和后到提交均不触发模型', async () => {
  const d = await device(); const body = input(); const mock = vi.fn(); vi.stubGlobal('fetch', mock);
  for (let i = 0; i < 2; i++) {
    const response = await purge(d, body);
    expect(response.status).toBe(200); expect(response.headers.get('Cache-Control')).toBe('no-store');
    expect(await response.json()).toEqual({ requestId: body.clientRequestId, purged: true });
  }
  const stub = env.AGENT_RUNTIME.getByName(d.owner); await evictDurableObject(stub);
  const ctx = createExecutionContext();
  const rejected = await worker.fetch(request(d, 'tasks', 'POST', body), env, ctx);
  expect(rejected.status).toBe(410); expect(await rejected.json()).toEqual({ error: 'request_expired' });
  expect((await worker.fetch(request(d, `requests/${body.clientRequestId}`), env, ctx)).status).toBe(404);
  expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks WHERE owner_id=?').bind(d.owner).first('n')).toBe(0);
  expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM agent_request_purges WHERE owner_id=?').bind(d.owner).first('n')).toBe(1);
  await waitOnExecutionContext(ctx); expect(mock).not.toHaveBeenCalled();
});

it.each(['available', 'acknowledged', 'withdrawn'])('原状态 %s 也必须实际清除 DO 事件；重复和逐出不恢复正文', async state => {
  const d = await device(); const body = input(); const { task, stub } = await completed(d, body);
  if (state === 'acknowledged') await tasks().acknowledge(d.header, task.id);
  if (state === 'withdrawn') await tasks().withdraw(d.header, task.id);
  expect((await storedEvents(stub, task.id)).events.length).toBeGreaterThan(0);
  for (let i = 0; i < 2; i++) {
    const response = await purge(d, body); expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ requestId: body.clientRequestId, purged: true });
    expect(await storedEvents(stub, task.id)).toEqual({ streams: [], events: [] });
  }
  await evictDurableObject(stub);
  expect(await storedEvents(stub, task.id)).toEqual({ streams: [], events: [] });
  expect(await stub.events(d.header, task.id, 0)).toEqual({ ok: false, error: 'not_found' });
  const row = await env.DB.prepare('SELECT status,result_state,result_json,payload_digest FROM agent_cloud_tasks WHERE id=?').bind(task.id).first();
  expect(row).toEqual({ status: 'completed', result_state: 'withdrawn', result_json: null, payload_digest: null });
});

it('同账户其他设备不能代删，其他账户同请求键独立； get/find 不泄露他人正文', async () => {
  const d = await device(); const peer = await device(d.owner); const outsider = await device(); const body = input();
  const { task, stub } = await completed(d, body); const before = await storedEvents(stub, task.id);
  const denied = await purge(peer, body); expect(denied.status).toBe(404); expect(await denied.json()).toEqual({ error: 'not_found' });
  expect(await purge(outsider, body).then(response => response.json())).toEqual({ requestId: body.clientRequestId, purged: true });
  const ctx = createExecutionContext();
  for (const path of [`tasks/${task.id}`, `requests/${body.clientRequestId}`]) {
    const response = await worker.fetch(request(outsider, path), env, ctx);
    expect(response.status).toBe(404); expect(await response.json()).toEqual({ error: 'not_found' });
  }
  expect(await storedEvents(stub, task.id)).toEqual(before);
  expect((await tasks().get(d.header, task.id)).result.answer).toBe('合成 D1 正文');
  expect(await purge(d, body).then(response => response.json())).toEqual({ requestId: body.clientRequestId, purged: true });
  await waitOnExecutionContext(ctx);
});

it.each(['throw', 'rejected', 'mismatch'])('DO 通知 %s 不返回清理成功；D1 拒绝键保留且原设备可重试', async failure => {
  const d = await device(); const body = input();
  const bindings = { ...env, AGENT_RUNTIME: { getByName: () => ({ async purgeRequest() {
    if (failure === 'throw') throw new Error('合成 DO 不可用');
    return failure === 'rejected' ? { ok: false, error: 'unavailable' } : { ok: true, requestId: crypto.randomUUID(), purged: true };
  } }) } };
  const response = await purge(d, body, bindings);
  expect(response.status).toBe(503); expect(await response.json()).toEqual({ error: 'unavailable' });
  await expect(tasks().accept(d.header, body)).rejects.toThrow('request_expired');
  expect(await purge(d, body).then(result => result.json())).toEqual({ requestId: body.clientRequestId, purged: true });
});

it('DO 清理 SQL 失败拒绝成功回执；重试真实清除旧 ACK 遗留正文', async () => {
  const d = await device(); const body = input(); const { task, stub } = await completed(d, body);
  await tasks().acknowledge(d.header, task.id);
  await runInDurableObject(stub, (_instance, state) => state.storage.sql.exec(`CREATE TRIGGER reject_purge_fixture
    BEFORE DELETE ON runtime_task_events WHEN OLD.task_id='${task.id}' BEGIN SELECT RAISE(ABORT,'合成清理故障'); END`));
  try {
    const response = await purge(d, body); expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: 'unavailable' });
    expect((await tasks().get(d.header, task.id)).result).toBeNull();
    expect((await storedEvents(stub, task.id)).events.length).toBeGreaterThan(0);
    await expect(tasks().accept(d.header, body)).rejects.toThrow('request_expired');
  } finally { await runInDurableObject(stub, (_instance, state) => state.storage.sql.exec('DROP TRIGGER reject_purge_fixture')); }
  expect((await purge(d, body)).status).toBe(200);
  expect(await storedEvents(stub, task.id)).toEqual({ streams: [], events: [] });
});

const chunk = (content, finish = null) => `data: ${JSON.stringify({ id: 'fixture_completion', object: 'chat.completion.chunk',
  choices: [{ index: 0, delta: { content }, finish_reason: finish }],
  usage: finish ? { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } : null })}\n\n`;
it('活动流清理停止原生成，迟到正文与收尾不能重建事件，账务派发证据仍保留', async () => {
  const d = await device(); const body = input(); const ready = deferred(); let feed; let signal;
  const mock = vi.fn(async (_url, init) => { signal = init.signal; return new Response(new ReadableStream({ start(controller) {
    feed = controller; controller.enqueue(new TextEncoder().encode(chunk('删除前合成暂存'))); ready.resolve();
  } }), { headers: { 'Content-Type': 'text/event-stream' } }); });
  vi.stubGlobal('fetch', mock); const ctx = createExecutionContext();
  const response = await worker.fetch(request(d, 'tasks', 'POST', body, true), env, ctx);
  expect(response.status).toBe(202); const { task } = await response.json(); await ready.promise;
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  try {
    await until(() => storedEvents(stub, task.id), value => JSON.stringify(value).includes('删除前合成暂存'));
    const reservation = await env.DB.prepare('SELECT * FROM agent_budget_reservations WHERE task_id=?').bind(task.id).all();
    expect(reservation.results).toHaveLength(1); expect(reservation.results[0].dispatch_state).toBe('may_have_dispatched');
    const purged = await purge(d, body); expect(purged.status).toBe(200);
    expect(await purged.json()).toEqual({ requestId: body.clientRequestId, purged: true });
    expect(signal.aborted).toBe(true);
    try { feed.enqueue(new TextEncoder().encode(chunk('禁止迟到正文') + chunk('', 'stop') + 'data: [DONE]\n\n')); } catch {}
    try { feed.close(); } catch {}
    await waitOnExecutionContext(ctx);
    expect(await storedEvents(stub, task.id)).toEqual({ streams: [], events: [] });
    expect((await tasks().get(d.header, task.id)).result).toBeNull();
    const retained = await env.DB.prepare('SELECT * FROM agent_budget_reservations WHERE task_id=?').bind(task.id).all();
    expect(retained.results).toHaveLength(1); expect(retained.results[0].id).toBe(reservation.results[0].id);
    expect(retained.results[0].dispatch_state).toBe('may_have_dispatched');
    expect((await worker.fetch(request(d, 'tasks', 'POST', body, true), env, ctx)).status).toBe(410);
    expect(mock).toHaveBeenCalledTimes(1);
  } finally { try { feed?.close(); } catch {} await waitOnExecutionContext(ctx); }
});

it.each(['before', 'after'])('D1 接单 %s 与清理交错，DO 等准备槽后清空且绝不派发模型', async phase => {
  const d = await device(); const body = input(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const entered = deferred(); const release = deferred(); const mock = vi.fn(); vi.stubGlobal('fetch', mock);
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, moment) => {
      if (moment === phase && sql.includes('INSERT INTO agent_cloud_tasks')) { entered.resolve(); await release.promise; }
    }) };
  });
  // 此处直接持有完整执行 RPC，避免测试回执的 HTTP 流跨另一个 RPC 保存。
  const submission = stub.execute(d.header, body, new Sink(), true);
  await entered.promise;
  let cleaning;
  try {
    // 与 HTTP 相同的先 D1、后 DO 顺序，竞争测试只传纯数据，不暂存跨 RPC 的响应流。
    await tasks().purgeRequest(d.header, body.clientRequestId);
    cleaning = env.AGENT_RUNTIME.getByName(d.owner).purgeRequest(d.header, body.clientRequestId);
  } finally { release.resolve(); }
  const [accepted, cleared] = await Promise.all([submission, cleaning]);
  expect(cleared).toEqual({ ok: true, requestId: body.clientRequestId, purged: true });
  if (phase === 'before') expect(accepted).toEqual({ ok: false, error: 'request_expired' });
  else {
    const task = await tasks().findByRequest(d.header, body.clientRequestId);
    expect(await storedEvents(env.AGENT_RUNTIME.getByName(d.owner), task.id)).toEqual({ streams: [], events: [] });
    expect((await tasks().get(d.header, task.id)).result).toBeNull();
  }
  expect(mock).not.toHaveBeenCalled();
});

it('正在等待原手机记忆工具时清理会关闭通道；迟到回执不再续跑模型', async () => {
  const d = await device(); const body = { ...input(), prompt: '我喜欢夜跑',
    conversation: { version: 'conversation_v1', id: crypto.randomUUID(), approved: true, messages: [] },
    memoryTools: { version: 'native_memory_v1', generation: crypto.randomUUID() } };
  const mock = vi.fn(async () => Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls', message: {
    role: 'assistant', content: null, tool_calls: [{ id: 'save_1', type: 'function', function: {
      name: 'memory_upsert', arguments: JSON.stringify({ category: 'preference', evidence: body.prompt, target: null }) } }],
  } }], usage: { prompt_tokens: 100, completion_tokens: 10, total_tokens: 110 } }));
  vi.stubGlobal('fetch', mock); const ctx = createExecutionContext();
  const response = await worker.fetch(request(d, 'tasks', 'POST', body), env, ctx);
  expect(response.status).toBe(202); const { task } = await response.json();
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await until(() => stub.memory(d.header, task.id), value => value.ok && value.memory !== null);
  const purged = await purge(d, body); expect(purged.status).toBe(200);
  expect(await stub.memory(d.header, task.id)).toEqual({ ok: false, error: 'inactive' });
  expect(await stub.memory(d.header, task.id, { version: 'native_memory_v1', generation: body.memoryTools.generation,
    callId: 'save_1', observation: { status: 'saved', memory: { id: crypto.randomUUID(), version: 1, content: body.prompt }, replayed: false } }))
    .toEqual({ ok: false, error: 'inactive' });
  await waitOnExecutionContext(ctx);
  expect((await tasks().get(d.header, task.id)).result).toBeNull(); expect(mock).toHaveBeenCalledTimes(1);
});
