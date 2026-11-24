import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDeepseekModel } from '../src/deepseek.ts';
import type { ModelPort } from '../src/contracts.ts';
import { TOOL_DEFINITIONS } from '../src/tools.ts';

const request: Parameters<ModelPort['generate']>[0] = {
  requestId: 'stream:1', instructions: '合成测试', messages: [{ role: 'user', text: '你好' }],
  tools: TOOL_DEFINITIONS, limits: { maxInputTokens: 20000, maxOutputTokens: 1600 },
};
const usage = { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 };
const chunk = (delta: unknown, finish: string | null = null, extra = {}) => ({
  id: 'completion_1', object: 'chat.completion.chunk',
  choices: [{ index: 0, delta, finish_reason: finish }], usage: finish ? usage : null, ...extra,
});
const event = (value: unknown) => `data: ${JSON.stringify(value)}\n\n`;
const done = 'data: [DONE]\n\n';
const response = (body: BodyInit) => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
const signal = () => new AbortController().signal;

test('真实增量先于结束到达，实际流式请求先计数且用量只保存一次', async () => {
  let feed!: ReadableStreamDefaultController<Uint8Array>;
  let first!: () => void;
  const received = new Promise<void>(resolve => { first = resolve; });
  let counted = '';
  let calls = 0;
  let cancelled = false;
  const deltas: string[] = [];
  const saved: unknown[] = [];
  const model = createDeepseekModel({ apiKey: 'fixture-only',
    countInputTokens: async raw => { counted = raw; return 100; },
    onTextDelta: async (id, text, current) => {
      assert.equal(id, request.requestId);
      assert.equal(current.aborted, false);
      deltas.push(text); first();
    },
    onUsage: async (_id, value) => { saved.push(value); },
    fetch: async (_url, init) => {
      calls++;
      assert.equal(init?.body, counted);
      assert.equal(JSON.parse(counted).stream, true);
      assert.deepEqual(JSON.parse(counted).stream_options, { include_usage: true });
      return response(new ReadableStream({ start(c) { feed = c; }, cancel() { cancelled = true; } }));
    },
  });
  let settled = false;
  const pending = model.generate(request, signal()).finally(() => { settled = true; });
  await new Promise<void>(resolve => setImmediate(resolve));
  feed.enqueue(new TextEncoder().encode(event(chunk({ role: 'assistant', content: '流萤' }))));
  await received;
  assert.equal(settled, false);
  assert.deepEqual(saved, []);
  feed.enqueue(new TextEncoder().encode(event(chunk({ content: '你好' })) + event(chunk({}, 'stop')) + done));
  assert.deepEqual(await pending, { decision: { kind: 'final', text: '流萤你好' },
    usage: { inputTokens: 100, outputTokens: 20 } });
  assert.deepEqual(deltas, ['流萤', '你好']);
  assert.deepEqual(saved, [{ inputTokens: 100, outputTokens: 20 }]);
  assert.equal(calls, 1);
  assert.equal(cancelled, true);
});

test('逐字节 UTF-8、BOM、多行 data 和三种换行不破坏片段', async () => {
  for (const newline of ['\n', '\r\n', '\r']) {
    const payload = '\uFEFF: 注释' + newline + newline +
      'data: {"id":"completion_1","object":"chat.completion.chunk",' + newline +
      'data: "choices":[{"index":0,"delta":{"role":"assistant","content":"A流🏃\\uFEFFB"},"finish_reason":null}]}' + newline + newline +
      (event(chunk({}, 'stop')) + done).replaceAll('\n', newline);
    const bytes = new TextEncoder().encode(payload);
    let offset = 0;
    const deltas: string[] = [];
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async (_id, text) => { deltas.push(text); },
      fetch: async () => response(new ReadableStream({ pull(c) {
        if (offset === bytes.length) c.close(); else c.enqueue(bytes.slice(offset, ++offset));
      } })),
    });
    assert.deepEqual((await model.generate(request, signal())).decision, { kind: 'final', text: 'A流🏃\uFEFFB' });
    assert.deepEqual(deltas, ['A流🏃\uFEFFB']);
  }
});

test('分块工具参数必须完整且经过原有校验，片段本身不会执行工具', async () => {
  const parts = [chunk({ role: 'assistant', content: '先查看记录。' }),
    chunk({ tool_calls: [{ index: 0, id: 'call_1', type: 'function', function: { name: 'runs_', arguments: '{"run' } }] }),
    chunk({ tool_calls: [{ index: 0, function: { name: 'summary', arguments: 'Id":"run_1"}' } }] }),
    chunk({}, 'tool_calls')];
  const deltas: string[] = [];
  const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
    onTextDelta: async (_id, text) => { deltas.push(text); },
    fetch: async () => response(parts.map(event).join('') + done),
  });
  assert.deepEqual((await model.generate(request, signal())).decision,
    { kind: 'tools', calls: [{ id: 'call_1', name: 'runs.summary', args: { runId: 'run_1' } }] });
  assert.deepEqual(deltas, ['先查看记录。']);
  parts[2] = chunk({ tool_calls: [{ index: 0, function: { name: 'summary', arguments: 'Id":' } }] });
  assert.equal((await model.generate(request, signal())).decision, null);
});

test('CR 终止事件不等待连接关闭，CRLF 拆块也不会提前分发多行 data', async () => {
  let cancelled = false;
  const payload = (event(chunk({ role: 'assistant', content: '完整回答' })) + event(chunk({}, 'stop')) + done).replaceAll('\n', '\r');
  const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
    onTextDelta: async () => {}, fetch: async () => response(new ReadableStream({ start(c) {
      c.enqueue(new TextEncoder().encode(payload));
    }, cancel() { cancelled = true; } })),
  });
  assert.deepEqual((await model.generate(request, signal())).decision, { kind: 'final', text: '完整回答' });
  assert.equal(cancelled, true);
});

test('缺少终止、断流、混入其他回答或推理均失败，不保存成功用量或自动重试', async () => {
  const cases = [
    event(chunk({ content: '未完成' })),
    event(chunk({ content: '未完成' })) + done,
    event(chunk({}, 'stop')),
    event(chunk({ content: '甲' })) + event(chunk({ content: '乙' }, null, { id: 'other' })) + done,
    event(chunk({ reasoning_content: '隐藏推理' })) + done,
    event(chunk({ content: '甲' }, null, { choices: [] })) + done,
    event(chunk({ content: '甲' })) + event(chunk({}, 'stop')) + event(chunk({ content: '迟到' })) + done,
    'data: {PRIVATE-BROKEN}\n\n',
    'data: ' + 'x'.repeat(65537),
    ': ' + 'x'.repeat(2 * 1024 * 1024),
  ];
  for (const payload of cases) {
    let calls = 0;
    let saved = 0;
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async () => {}, onUsage: async () => { saved++; },
      fetch: async () => { calls++; return response(payload); },
    });
    await assert.rejects(model.generate(request, signal()), /^Error: model_unavailable$/);
    assert.equal(calls, 1); assert.equal(saved, 0);
  }
});

test('取消等待正文或取消片段回调会关闭流，不产生迟到片段或结果', async () => {
  for (const duringCallback of [false, true]) {
    const abort = new AbortController();
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    let cancelled = false;
    let pieces = 0;
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async () => { pieces++; entered(); await held; },
      fetch: async () => response(new ReadableStream({ start(c) {
        if (duringCallback) c.enqueue(new TextEncoder().encode(event(chunk({ content: '片段' })) + event(chunk({ content: '迟到' }))));
      }, pull() { if (!duringCallback) entered(); }, cancel() { cancelled = true; } })),
    });
    const pending = model.generate(request, abort.signal);
    await started; abort.abort();
    await assert.rejects(pending, /model_unavailable/);
    release();
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(cancelled, true); assert.equal(pieces, duringCallback ? 1 : 0);
  }
});

test('流式拒绝畸形工具、超长正文、非法 UTF-8、非 SSE 和 HTTP 错误', async () => {
  const tool = { index: 0, id: 'call_1', type: 'function', function: { name: 'runs_list', arguments: '{}' } };
  const deltas = [
    { content: '字'.repeat(4001) }, { content: 123 }, { role: 'system', content: '伪造角色' },
    { tool_calls: [{ ...tool, index: 1 }] }, { tool_calls: Array(7).fill(tool) },
    { tool_calls: [{ ...tool, type: 'other' }] },
    { tool_calls: [{ ...tool, function: { name: 'x'.repeat(129), arguments: '{}' } }] },
  ];
  const bodies: Response[] = deltas.map(delta => response(event(chunk(delta)) + event(chunk({}, 'stop')) + done));
  bodies.push(response(new Uint8Array([0xff])), new Response('{}'),
    new Response('PRIVATE', { status: 503, headers: { 'Content-Type': 'text/event-stream' } }));
  for (const reply of bodies) {
    let calls = 0;
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async () => {}, fetch: async () => { calls++; return reply; } });
    await assert.rejects(model.generate(request, signal()), /^Error: model_unavailable$/);
    assert.equal(calls, 1);
  }
});

test('停止原因仍走旧校验，缺失或矛盾用量不造零，宿主回调失败不降级重发', async (t) => {
  t.mock.method(console, 'warn', () => {});
  for (const finish of ['length', 'content_filter', 'insufficient_system_resource']) {
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async () => {}, fetch: async () => response(event(chunk({ content: '未完成' })) + event(chunk({}, finish)) + done) });
    assert.deepEqual(await model.generate(request, signal()), { decision: null, usage: { inputTokens: 100, outputTokens: 20 } });
  }
  for (const value of [null, { ...usage, total_tokens: 0 }]) {
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
      onTextDelta: async () => {}, fetch: async () => response(event(chunk({ content: '你好' })) + event(chunk({}, 'stop', { usage: value })) + done) });
    assert.equal((await model.generate(request, signal())).usage, null);
  }
  let calls = 0;
  let cancelled = false;
  const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
    onTextDelta: async () => { throw new Error('PRIVATE-CALLBACK'); },
    fetch: async () => { calls++; return response(new ReadableStream({ start(c) {
      c.enqueue(new TextEncoder().encode(event(chunk({ content: '片段' }))));
    }, cancel() { cancelled = true; } })); },
  });
  await assert.rejects(model.generate(request, signal()), /^Error: model_unavailable$/);
  assert.equal(calls, 1); assert.equal(cancelled, true);
});

test('流式片段宿主永久等待也受原有30秒截止约束', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let entered!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  let cancelled = false;
  const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
    onTextDelta: async () => { entered(); await new Promise(() => {}); },
    fetch: async () => response(new ReadableStream({ start(c) {
      c.enqueue(new TextEncoder().encode(event(chunk({ content: '片段' }))));
    }, cancel() { cancelled = true; } })),
  });
  const pending = model.generate(request, signal());
  await started; t.mock.timers.tick(30000);
  await assert.rejects(pending, /model_unavailable/);
  assert.equal(cancelled, true);
});
