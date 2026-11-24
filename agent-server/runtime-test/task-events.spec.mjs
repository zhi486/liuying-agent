import { env, RpcTarget } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext,
  runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { TaskEventStore } from '../src/task-event-store.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const event = (delta, finish = null) => `data: ${JSON.stringify({ id: 'fixture_completion', object: 'chat.completion.chunk',
  choices: [{ index: 0, delta, finish_reason: finish }],
  usage: finish ? { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } : null })}\n\n`;
const reply = body => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
const input = () => ({ clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(), prompt: '你好',
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [] });
async function device(owner = crypto.randomUUID()) {
  await env.DB.prepare('INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const issued = await createDeviceAdmin({ db: env.DB }).issue(owner);
  return { owner, deviceId: issued.deviceId, header: `Bearer ${issued.token}` };
}
const request = (d, path, method = 'GET', body, streaming = false) => new Request(`https://local.invalid/v1/agent/${path}`, {
  method, headers: { Authorization: d.header, 'Content-Type': 'application/json', ...(streaming ? { 'X-Agent-Stream': 'task_events_v1' } : {}) },
  ...(body ? { body: JSON.stringify(body) } : {}),
});
class Sink extends RpcTarget { async accepted() {} }
async function collect(response) {
  const text = await response.text();
  return text.split('\n').filter(line => line.startsWith('data: ')).map(line => JSON.parse(line.slice(6)));
}

it('正式HTTP入口在答案完成前交付真实增量；断开再续读及重放都不重新生成', async () => {
  const d = await device(); const body = input(); const dispatched = deferred();
  let feed;
  const mock = vi.fn(async (_url, init) => {
    expect(JSON.parse(init.body).stream).toBe(true);
    const response = reply(new ReadableStream({ start(c) {
      feed = c; c.enqueue(new TextEncoder().encode(event({ role: 'assistant', content: '合成前缀' })));
    } })); dispatched.resolve(); return response;
  });
  vi.stubGlobal('fetch', mock);
  const ctx = createExecutionContext();
  let reader;
  try {
    const accepted = await worker.fetch(request(d, 'tasks', 'POST', body, true), env, ctx);
    expect(accepted.status).toBe(202);
    const { task } = await accepted.json(); await dispatched.promise;
    const source = await worker.fetch(request(d, `tasks/${task.id}/events/0`), env, ctx);
    expect(source.status).toBe(200); expect(source.headers.get('Cache-Control')).toBe('no-store');
    reader = source.body.getReader();
    let text = '';
    while (!text.includes('合成前缀')) text += new TextDecoder().decode((await reader.read()).value);
    expect(text).toContain('event: text_delta');
    expect((await createCloudTasks({ db: env.DB }).get(d.header, task.id)).status).toBe('running');
    const received = text.split('\n').filter(line => line.startsWith('data: ')).map(line => JSON.parse(line.slice(6)));
    const cursor = received.at(-1).sequence;
    await reader.cancel(); reader = undefined;
    feed.enqueue(new TextEncoder().encode(event({ content: '，尾句。' }) + event({}, 'stop') + 'data: [DONE]\n\n'));
    await waitOnExecutionContext(ctx);
    const reconnected = await worker.fetch(request(d, `tasks/${task.id}/events/${cursor}`), env, ctx);
    const rest = await collect(reconnected);
    expect(rest[0].sequence).toBe(cursor + 1);
    expect(rest.filter(row => row.kind === 'text_delta').map(row => row.text).join('')).toBe('，尾句。');
    expect(rest.at(-1)).toMatchObject({ kind: 'task_end', status: 'completed' });
    const repeated = await worker.fetch(request(d, 'tasks', 'POST', body, true), env, ctx);
    expect((await repeated.json()).task.id).toBe(task.id);
    await waitOnExecutionContext(ctx);
    expect(mock).toHaveBeenCalledTimes(1);
    expect((await createCloudTasks({ db: env.DB }).get(d.header, task.id)).result.answer).toBe('合成前缀，尾句。');
  } finally {
    await reader?.cancel();
    try { feed?.close(); } catch {}
    await waitOnExecutionContext(ctx);
  }
});

it('工具轮说明与最终答案分轮，部分参数不执行工具；完整工具只执行一次', async () => {
  const d = await device(); const body = { ...input(), prompt: '复盘', consent: { cloudProcessing: true, runSummaries: true },
    summaries: [{ runId: 'run_1', distanceMeters: 1000, durationSeconds: 360, averagePaceSecPerKm: 360, averageHeartRate: 140 }] };
  let calls = 0;
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    calls++;
    if (calls === 1) return reply(event({ role: 'assistant', content: '先查看摘要。', tool_calls: [
      { index: 0, id: 'tool_1', type: 'function', function: { name: 'runs_summary', arguments: '{"runId":' } }] }) +
      event({ tool_calls: [{ index: 0, function: { arguments: '"run_1"}' } }] }) + event({}, 'tool_calls') + 'data: [DONE]\n\n');
    expect(JSON.parse(init.body).messages.at(-1).tool_call_id).toBe('tool_1');
    return reply(event({ role: 'assistant', content: '最终合成回答。' }) + event({}, 'stop') + 'data: [DONE]\n\n');
  }));
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await stub.execute(d.header, body, new Sink(), true);
  expect(result.ok).toBe(true); expect(result.task.status).toBe('completed');
  const view = await stub.events(d.header, result.task.id, 0);
  expect(view.ok).toBe(true);
  expect(view.batch.events.filter(row => row.kind === 'round_end').map(row => [row.round, row.outcome]))
    .toEqual([[1, 'tools'], [2, 'answer']]);
  expect(view.batch.events.filter(row => row.kind === 'text_delta').map(row => [row.round, row.text]))
    .toEqual([[1, '先查看摘要。'], [2, '最终合成回答。']]);
  expect(calls).toBe(2);
  expect(result.task.result.actions.filter(row => row.kind === 'tool_completed')).toHaveLength(1);
});

it('完成后真实逐出DO再查询，序号和正文仍在且不恢复执行', async () => {
  const d = await device(); let calls = 0;
  vi.stubGlobal('fetch', vi.fn(async () => { calls++; return reply(event({ content: '持久合成文本' }) + event({}, 'stop') + 'data: [DONE]\n\n'); }));
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await stub.execute(d.header, input(), new Sink(), true);
  const before = await stub.events(d.header, result.task.id, 0);
  await evictDurableObject(stub);
  expect(await stub.events(d.header, result.task.id, 0)).toEqual(before);
  expect(calls).toBe(1);
});

it('其他owner、同owner其他设备、撤回与确认后均不能取回暂存正文', async () => {
  const d = await device(); const peer = await device(d.owner); const outsider = await device();
  vi.stubGlobal('fetch', vi.fn(async () => reply(event({ content: '不可泄露的合成正文' }) + event({}, 'stop') + 'data: [DONE]\n\n')));
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  for (const action of ['withdraw', 'acknowledge']) {
    const result = await stub.execute(d.header, input(), new Sink(), true);
    expect(await stub.events(peer.header, result.task.id, 0)).toEqual({ ok: false, error: 'unauthenticated' });
    expect(await stub.events(outsider.header, result.task.id, 0)).toEqual({ ok: false, error: 'unauthenticated' });
    await createCloudTasks({ db: env.DB })[action](d.header, result.task.id);
    expect(await stub.events(d.header, result.task.id, 0)).toEqual({ ok: false, error: 'inactive' });
    const rows = await runInDurableObject(stub, (_instance, state) => state.storage.sql.exec(
      'SELECT COUNT(*) AS n FROM runtime_task_events WHERE task_id=?', result.task.id).one().n);
    expect(rows).toBe(0);
  }
});

it('预检后撤回、读取前撤销设备，首批正文不会因缓存而泄露', async () => {
  for (const change of ['withdraw', 'revoke']) {
    const d = await device();
    vi.stubGlobal('fetch', vi.fn(async () => reply(event({ content: 'PRIVATE-FIXTURE' }) + event({}, 'stop') + 'data: [DONE]\n\n')));
    const stub = env.AGENT_RUNTIME.getByName(d.owner);
    const result = await stub.execute(d.header, input(), new Sink(), true);
    const ctx = createExecutionContext();
    const response = await worker.fetch(request(d, `tasks/${result.task.id}/events/0`), env, ctx);
    expect(response.status).toBe(200);
    if (change === 'withdraw') await createCloudTasks({ db: env.DB }).withdraw(d.header, result.task.id);
    else await env.DB.prepare('UPDATE agent_devices SET revoked_at=? WHERE id=?').bind(Date.now(), d.deviceId).run();
    const text = await response.text();
    expect(text).not.toContain('PRIVATE-FIXTURE');
    expect(text).toContain('stream_error');
  }
});

it('SQLite事件插入失败整笔回滚，序号不提前增长；终态后拒绝迟到文字', async () => {
  const d = await device(); const stub = env.AGENT_RUNTIME.getByName(d.owner); const taskId = crypto.randomUUID();
  await runInDurableObject(stub, async (_instance, state) => {
    const store = new TaskEventStore(state.storage); store.start(taskId, d.deviceId);
    const port = store.port(taskId, signal => signal.throwIfAborted()); const signal = new AbortController().signal;
    port.begin(`${taskId}:1`, signal);
    state.storage.sql.exec(`CREATE TRIGGER reject_fixture_event BEFORE INSERT ON runtime_task_events
      WHEN NEW.task_id='${taskId}' BEGIN SELECT RAISE(ABORT,'fixture'); END`);
    expect(() => port.end(`${taskId}:1`, 'answer', signal)).toThrow();
    expect(state.storage.sql.exec('SELECT seq FROM runtime_task_streams WHERE task_id=?', taskId).one().seq).toBe(1);
    state.storage.sql.exec('DROP TRIGGER reject_fixture_event');
    port.end(`${taskId}:1`, 'answer', signal);
    expect(() => port.end(`${taskId}:1`, 'answer', signal)).toThrow();
    store.finish({ id: taskId, status: 'completed' });
    await expect(port.text(`${taskId}:1`, '迟到正文', signal)).rejects.toThrow();
  });
});

it('供应商响应意外截断只保留暂存片段，最终任务失败而不是完成', async () => {
  const d = await device();
  const mock = vi.fn(async () => reply(event({ content: '不完整的合成回答' })));
  vi.stubGlobal('fetch', mock);
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await stub.execute(d.header, input(), new Sink(), true);
  expect(result.ok).toBe(true); expect(result.task.status).toBe('failed');
  expect(result.task.result).toMatchObject({ status: 'failed', reason: 'model_unavailable', cost: { usageComplete: false } });
  expect(result.task.result.answer).toBeUndefined();
  const view = await stub.events(d.header, result.task.id, 0);
  expect(view.ok).toBe(true);
  expect(view.batch.events.some(row => row.kind === 'text_delta')).toBe(true);
  expect(view.batch.events.some(row => row.kind === 'round_end' && row.outcome === 'answer')).toBe(false);
  expect(view.batch.events.at(-1)).toMatchObject({ kind: 'task_end', status: 'failed' });
  expect(mock).toHaveBeenCalledTimes(1);
});

it('生成中取消收敛原任务，迟到正文不能提交也不会自动生成第二次', async () => {
  const d = await device(); const ready = deferred(); let feed;
  const mock = vi.fn(async () => reply(new ReadableStream({ start(c) {
    feed = c; c.enqueue(new TextEncoder().encode(event({ content: '取消前暂存' }))); ready.resolve();
  } })));
  vi.stubGlobal('fetch', mock);
  const ctx = createExecutionContext(); let reader;
  try {
    const accepted = await worker.fetch(request(d, 'tasks', 'POST', input(), true), env, ctx);
    const { task } = await accepted.json(); await ready.promise;
    const response = await worker.fetch(request(d, `tasks/${task.id}/events/0`), env, ctx);
    reader = response.body.getReader(); let text = '';
    while (!text.includes('取消前暂存')) text += new TextDecoder().decode((await reader.read()).value);
    await reader.cancel(); reader = undefined;
    const cancelled = await worker.fetch(request(d, `tasks/${task.id}/cancel`, 'POST'), env, ctx);
    expect(cancelled.status).toBe(200);
    await waitOnExecutionContext(ctx);
    try { feed.enqueue(new TextEncoder().encode(event({ content: '禁止迟到正文' }) + event({}, 'stop') + 'data: [DONE]\n\n')); } catch {}
    const view = await env.AGENT_RUNTIME.getByName(d.owner).events(d.header, task.id, 0);
    expect(view.ok).toBe(true);
    expect(JSON.stringify(view.batch)).not.toContain('禁止迟到正文');
    expect(view.batch.events.at(-1)).toMatchObject({ kind: 'task_end', status: 'cancelled' });
    expect((await createCloudTasks({ db: env.DB }).get(d.header, task.id)).result).toBeNull();
    expect(mock).toHaveBeenCalledTimes(1);
  } finally {
    await reader?.cancel(); try { feed?.close(); } catch {}
    await waitOnExecutionContext(ctx);
  }
});

it('超过64条事件按游标连续读取；到期拒绝读取并惰性清除暂存正文', async () => {
  const d = await device(); const stub = env.AGENT_RUNTIME.getByName(d.owner); const taskId = crypto.randomUUID();
  await runInDurableObject(stub, async (_instance, state) => {
    const store = new TaskEventStore(state.storage); store.start(taskId, d.deviceId);
    const port = store.port(taskId, signal => signal.throwIfAborted()); const signal = new AbortController().signal;
    port.begin(`${taskId}:1`, signal);
    for (let i = 0; i < 70; i++) await port.text(`${taskId}:1`, '字', signal);
    port.end(`${taskId}:1`, 'answer', signal);
    const task = { id: taskId, status: 'completed', resultState: 'available' };
    store.finish(task);
    const first = store.read(task, d.deviceId, 0); const second = store.read(task, d.deviceId, first.cursor);
    expect(first.events).toHaveLength(64); expect(first.terminal).toBe(false);
    expect(second.terminal).toBe(true);
    expect([...first.events, ...second.events].map(row => row.sequence)).toEqual(Array.from({ length: 73 }, (_, i) => i + 1));
    expect(store.read(task, d.deviceId, second.cursor).events).toEqual([]);
    expect(() => store.read(task, d.deviceId, second.cursor + 1)).toThrow();
    state.storage.sql.exec('UPDATE runtime_task_streams SET created_at=? WHERE task_id=?', Date.now() - 86400001, taskId);
    expect(() => store.read(task, d.deviceId, 0)).toThrow();
    expect(state.storage.sql.exec('SELECT COUNT(*) AS n FROM runtime_task_events WHERE task_id=?', taskId).one().n).toBe(0);
  });
});
