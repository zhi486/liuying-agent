import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseHttpRequest } from '../src/agent-http.ts';
import { eventCursor, taskEventRound, taskEventsResponse, type TaskEvent, type TaskEventBatch } from '../src/task-events.ts';
import { input } from './execution-fixture.ts';

const id = '17f23bb3-25a0-4412-a57d-5678c74b720a';
const root = `https://agent.invalid/v1/agent/tasks/${id}/events/`;
const event = (sequence: number): TaskEvent => ({ version: 'task_events_v1', taskId: id,
  sequence, round: 1, kind: 'text_delta', text: `片段${sequence}` });
const batch = (events: TaskEvent[], terminal = false): TaskEventBatch => ({ version: 'task_events_v1',
  taskId: id, events, cursor: events.at(-1)?.sequence ?? 0, terminal });

test('只有规范游标和原任务事件入口可读，流式传输须显式请求且不改变任务正文', async () => {
  for (const after of [0, 1, 12016]) assert.deepEqual(await parseHttpRequest(new Request(root + after)), { kind: 'events', id, after });
  for (const after of ['-1', '01', '1.0', '1e2', '12017', '%31', '1/2', 'NaN'])
    await assert.rejects(parseHttpRequest(new Request(root + after)), /invalid_request/);
  await assert.rejects(parseHttpRequest(new Request(root + '0', { method: 'POST' })), /invalid_request/);
  const data = input();
  const submit = (value: string) => new Request('https://agent.invalid/v1/agent/tasks', { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Agent-Stream': value }, body: JSON.stringify(data) });
  assert.deepEqual(await parseHttpRequest(submit('task_events_v1')), { kind: 'submit', streaming: true, input: data });
  await assert.rejects(parseHttpRequest(submit('true')), /invalid_request/);
});

test('轮次只接受原任务的1至3轮，游标不能通过数字转换放宽', () => {
  for (let round = 1; round <= 3; round++) assert.equal(taskEventRound(id, `${id}:${round}`), round);
  for (const suffix of ['0', '4', '01', '1.0', '1e0', '-1']) assert.throws(() => taskEventRound(id, `${id}:${suffix}`));
  assert.throws(() => taskEventRound(id, `other:${1}`));
  for (const value of [0, null, undefined, '', '000', '12017']) assert.throws(() => eventCursor(value));
});

test('SSE按连续序号交付、结束后关闭，未知查询错误不泄露细节', async () => {
  const cursors: number[] = [];
  const response = taskEventsResponse(async cursor => { cursors.push(cursor); return batch([event(cursor + 1)], true); }, 4, new AbortController().signal);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  const text = await response.text();
  assert.match(text, /id: 5\nevent: text_delta\ndata: /);
  assert.deepEqual(cursors, [4]);
  for (const load of [async () => batch([event(3)], true), async () => { throw new Error('PRIVATE'); }]) {
    const text = await taskEventsResponse(load, 0, new AbortController().signal).text();
    assert.equal(text, 'event: stream_error\ndata: {"error":"unavailable"}\n\n');
  }
});

test('取消订阅和请求中止均停止等待，迟到查询不交付正文、不取消模型任务', async () => {
  for (const abortRequest of [false, true]) {
    let release!: (value: TaskEventBatch) => void;
    const held = new Promise<TaskEventBatch>(resolve => { release = resolve; });
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    let loads = 0;
    const abort = new AbortController();
    const response = taskEventsResponse(async () => { loads++; entered(); return held; }, 0, abort.signal);
    const reader = response.body!.getReader();
    const reading = reader.read();
    await started;
    if (abortRequest) abort.abort(); else await reader.cancel();
    release(batch([event(1)]));
    assert.deepEqual(await reading, { done: true, value: undefined });
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(loads, 1);
    reader.releaseLock();
  }
});
