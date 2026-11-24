import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NativeMemoryChannel, parseNativeMemoryReply } from '../src/native-memory-channel.ts';
import { parseSubmission } from '../src/cloud-task-contracts.ts';
import { parseHttpRequest, errorResponse } from '../src/agent-http.ts';
import { runAgent } from '../src/loop.ts';
import { readFileSync } from 'node:fs';

const id = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const binding = { requestId: id, taskId: other, deviceId: id, generation: id };
const call = { id: 'save_1', name: 'memory.upsert', args: {
  category: 'preference', evidence: '我喜欢夜跑', target: null,
} } as const;
const saved = { status: 'saved', memory: { id, version: 1, content: call.args.evidence }, replayed: false };
const reply = (observation: unknown = saved) => ({ version: 'native_memory_v1', generation: id, callId: call.id, observation });
const input = () => ({ clientRequestId: id, localTaskId: other, prompt: call.args.evidence,
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
  conversation: { version: 'conversation_v1', id, approved: true, messages: [] },
  memoryTools: { version: 'native_memory_v1', generation: id } });

test('事实范围默认关闭；显式范围只影响调用类别，不改变回执传输版本', async () => {
  const fact = { ...call, args: { ...call.args, category: 'fact', evidence: '我的专业是软件工程' } } as const;
  const closed = new NativeMemoryChannel(binding);
  await assert.rejects(closed.execute(fact, new AbortController().signal), { code: 'scope_denied' });
  assert.equal(closed.poll(), null);
  closed.close();
  const allowed = new NativeMemoryChannel({ ...binding, factsAllowed: true });
  try {
    const pending = allowed.execute(fact, new AbortController().signal);
    assert.deepEqual(allowed.poll(), { version: 'native_memory_v1', requestId: id, taskId: other, generation: id, call: fact });
    const observation = { ...saved, memory: { ...saved.memory, content: fact.args.evidence } };
    allowed.reply(reply(observation));
    assert.deepEqual(await pending, observation);
  } finally { allowed.close(); }
});

test('服务端与手机共享同一份工具往返样例', async () => {
  const fixture = JSON.parse(readFileSync(new URL('../../contracts/memory-tools-v1/transport.json', import.meta.url), 'utf8'));
  const source = fixture.request.memory;
  const channel = new NativeMemoryChannel({ ...binding, generation: source.generation });
  const pending = channel.execute(source.call, new AbortController().signal);
  assert.deepEqual({ memory: channel.poll() }, fixture.request);
  channel.reply(fixture.reply);
  assert.deepEqual(await pending, fixture.reply.observation);
  channel.close();
});

test('协议元数据严格校验且保留入任务指纹；普通旧请求不补字段', async () => {
  const source = input();
  const parsed = parseSubmission(source);
  source.memoryTools.generation = other;
  assert.deepEqual(parsed.task.memoryTools, { version: 'native_memory_v1', generation: id });
  const { memoryTools: _tools, conversation: _conversation, ...legacy } = input();
  assert.equal(Object.hasOwn(parseSubmission(legacy).task, 'memoryTools'), false);
  assert.throws(() => parseSubmission({ ...legacy, memoryTools: input().memoryTools }));
  for (const memoryTools of [true, null, {}, { version: 'native_memory_v1', generation: 'bad' },
    { ...input().memoryTools, enabled: true }, { version: 'other', generation: id }])
    assert.throws(() => parseSubmission({ ...input(), memoryTools }));
  const requests: unknown[] = [];
  const result = await runAgent(parsed.task, {
    model: { async generate(request) { requests.push(request.tools); return {
      decision: { kind: 'final', text: '合成回答' }, usage: { inputTokens: 10, outputTokens: 3 },
    }; } },
    budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
  });
  assert.equal(result.status, 'completed');
  assert.deepEqual(requests, [[]]);
});

test('同一次工具轮询不会消费调用；相同回执可确认但不能改变结果', async () => {
  const channel = new NativeMemoryChannel(binding);
  const pending = channel.execute(call, new AbortController().signal);
  const polled = channel.poll();
  assert.deepEqual(polled, { version: 'native_memory_v1', requestId: id, taskId: other, generation: id, call });
  assert.deepEqual(channel.poll(), polled);
  channel.reply(reply());
  channel.reply(reply());
  assert.deepEqual(await pending, saved);
  assert.equal(channel.poll(), null);
  assert.throws(() => channel.reply(reply({ status: 'rejected', reason: 'conflict' })), { code: 'conflict' });
  await assert.rejects(channel.execute(call, new AbortController().signal), { code: 'inactive' });
  channel.close();
  assert.throws(() => channel.reply(reply()), { code: 'inactive' });
});

test('旧代际和错误调用 ID 不消费正在等待的调用', async () => {
  const channel = new NativeMemoryChannel(binding);
  const pending = channel.execute(call, new AbortController().signal);
  assert.throws(() => channel.reply({ ...reply(), generation: other }), { code: 'conflict' });
  assert.throws(() => channel.reply({ ...reply(), callId: 'other' }), { code: 'inactive' });
  assert.equal(channel.poll()?.call.id, call.id);
  channel.reply(reply({ status: 'rejected', reason: 'consent_required' }));
  assert.deepEqual(await pending, { status: 'rejected', reason: 'consent_required' });
  channel.close();
});

test('无效回执只返回未知，错误正文不进入后续模型', async () => {
  const channel = new NativeMemoryChannel(binding);
  const pending = channel.execute(call, new AbortController().signal);
  channel.reply(reply({ status: 'saved', memory: { ...saved.memory, content: 'PRIVATE-ERROR' }, replayed: false }));
  assert.deepEqual(await pending, { status: 'unconfirmed', reason: 'invalid_response' });
  channel.close();
});

test('手机失联有界退出；迟到响应不续跑，也不重新派发相同 ID', async () => {
  const channel = new NativeMemoryChannel(binding, 10);
  assert.deepEqual(await channel.execute(call, new AbortController().signal), { status: 'unconfirmed', reason: 'unavailable' });
  assert.equal(channel.poll(), null);
  assert.throws(() => channel.reply(reply()), { code: 'inactive' });
  await assert.rejects(channel.execute(call, new AbortController().signal), { code: 'inactive' });
  channel.close();
});

test('取消和宿主结束都清除待交付正文，迟到结果不复活', async () => {
  for (const action of ['abort', 'close']) {
    const channel = new NativeMemoryChannel(binding);
    const controller = new AbortController();
    const pending = channel.execute(call, controller.signal);
    if (action === 'abort') controller.abort(); else channel.close();
    assert.deepEqual(await pending, { status: 'unconfirmed', reason: 'unavailable' });
    assert.throws(() => channel.reply(reply()), { code: 'inactive' });
    channel.close();
  }
});

test('HTTP 只允许固定工具往返端点、结构、UTF-8 和有界正文', async () => {
  const url = `https://local.invalid/v1/agent/tasks/${id}/memory`;
  assert.deepEqual(await parseHttpRequest(new Request(url)), { kind: 'memory', id });
  const request = (body: BodyInit, headers = { 'Content-Type': 'application/json' }) =>
    new Request(url, { method: 'POST', headers, body });
  assert.deepEqual(await parseHttpRequest(request(JSON.stringify(reply()))), { kind: 'memory', id, reply: reply() });
  for (const value of [{ ...reply(), ownerId: id }, { ...reply(), callId: '../other' },
    { ...reply(), generation: 'bad' }, { ...reply(), version: 'other' }])
    assert.throws(() => parseNativeMemoryReply(value));
  for (const [req, status] of [
    [request('x'.repeat(16385)), 413], [request('{}', { 'Content-Type': 'text/plain' }), 415],
    [request(new Uint8Array([0xff])), 400], [new Request(url, { method: 'DELETE' }), 400],
    [new Request(`${url}?ownerId=${id}`), 400],
  ] as const) {
    try { await parseHttpRequest(req); assert.fail(); }
    catch (error) { assert.equal(errorResponse(error).status, status); }
  }
});
