import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { ModelPort } from '../src/contracts.ts';
import { TOOL_DEFINITIONS } from '../src/tools.ts';
import { createDeepseekModel } from '../src/deepseek.ts';

const request: Parameters<ModelPort['generate']>[0] = {
  requestId: 'task_1:1',
  instructions: '只读运动摘要',
  messages: [{ role: 'user', text: '复盘本次跑步' }],
  tools: TOOL_DEFINITIONS,
  limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
};
const wireReply = {
  id: 'completion_1',
  object: 'chat.completion',
  created: 1789380000,
  model: 'deepseek-flash',
  system_fingerprint: 'test',
  choices: [
    {
      index: 0,
      finish_reason: 'stop',
      logprobs: null,
      message: { role: 'assistant', content: '本次四公里，用时三十分钟。' },
    },
  ],
  usage: {
    prompt_tokens: 100,
    completion_tokens: 40,
    total_tokens: 140,
    prompt_cache_hit_tokens: 0,
    prompt_cache_miss_tokens: 100,
    prompt_tokens_details: { cached_tokens: 0 },
  },
};

test('真实适配器将供应商完成归一化，固定模型与输出限制且不自动重试', async () => {
  const sent: { url: string; init: RequestInit }[] = [];
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async (url, init) => {
      sent.push({ url: String(url), init: init! });
      return Response.json(wireReply);
    },
  });
  const result = await model.generate(request, new AbortController().signal);
  assert.deepEqual(result, {
    decision: { kind: 'final', text: '本次四公里，用时三十分钟。' },
    usage: { inputTokens: 100, outputTokens: 40 },
  });
  assert.equal(sent.length, 1);
  assert.equal(sent[0]?.url, 'https://api.deepseek.com/chat/completions');
  assert.equal(sent[0]?.init.redirect, 'error');
  const body = JSON.parse(sent[0]!.init.body as string);
  assert.equal(body.model, 'deepseek-flash');
  assert.deepEqual(body.thinking, { type: 'disabled' });
  assert.equal(body.max_tokens, 600);
  assert.equal(body.stream, false);
  assert.deepEqual(body.messages, [
    { role: 'system', content: '只读运动摘要' },
    { role: 'user', content: '复盘本次跑步' },
  ]);
  assert.deepEqual(
    body.tools.map((t: { function: { name: string } }) => t.function.name),
    ['runs_list', 'runs_summary'],
  );
});

test('前导 UTF-8 BOM 分块返回仍可解码，正文内部 BOM 保留且用量只保存一次', async () => {
  const data = structuredClone(wireReply);
  data.choices[0]!.message.content = 'A\uFEFFB';
  const bytes = Uint8Array.from([
    0xef,
    0xbb,
    0xbf,
    ...new TextEncoder().encode(JSON.stringify(data)),
  ]);
  const saved: unknown[] = [];
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    onUsage: async (_id, usage) => {
      saved.push(usage);
    },
    fetch: async () =>
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(bytes.subarray(0, 1));
            controller.enqueue(bytes.subarray(1, 4));
            controller.enqueue(bytes.subarray(4));
            controller.close();
          },
        }),
      ),
  });
  const result = await model.generate(request, new AbortController().signal);
  assert.deepEqual(result.decision, { kind: 'final', text: 'A\uFEFFB' });
  assert.deepEqual(result.usage, { inputTokens: 100, outputTokens: 40 });
  assert.deepEqual(saved, [{ inputTokens: 100, outputTokens: 40 }]);
});

test('JSON 正文内部非法 UTF-8 必须静态失败，不保存看似有效的用量且不重试', async () => {
  const data = structuredClone(wireReply);
  data.choices[0]!.message.content = 'INVALID_UTF8';
  const [prefix, suffix] = JSON.stringify(data).split('INVALID_UTF8');
  const bytes = Uint8Array.from([
    ...new TextEncoder().encode(prefix!),
    0x80,
    ...new TextEncoder().encode(suffix!),
  ]);
  let sent = 0;
  const saved: unknown[] = [];
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    onUsage: async (_id, usage) => {
      saved.push(usage);
    },
    fetch: async () => {
      sent++;
      return new Response(bytes);
    },
  });
  await assert.rejects(
    model.generate(request, new AbortController().signal),
    /^Error: model_unavailable$/,
  );
  assert.equal(sent, 1);
  assert.deepEqual(saved, []);
});

const signal = () => new AbortController().signal;

for (const cancelledBeforeStart of [false, true])
  test(`宿主拒绝保活注册时安全停止且无 HTTP 或未处理拒绝：预先取消=${cancelledBeforeStart}`, async () => {
    let sent = 0;
    const controller = new AbortController();
    if (cancelledBeforeStart) controller.abort();
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async () => 100,
      waitUntil: () => {
        throw new Error('PRIVATE-HOST-CONTEXT');
      },
      fetch: async () => {
        sent++;
        return Response.json(wireReply);
      },
    });
    await assert.rejects(model.generate(request, controller.signal), /^Error: model_unavailable$/);
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(sent, 0);
  });

test('计数后才能申请派发许可，许可晚到时已取消就不能发 HTTP', async () => {
  const events: string[] = [];
  const controller = new AbortController();
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => {
      events.push('count');
      return 100;
    },
    fetch: async () => {
      events.push('fetch');
      return Response.json(wireReply);
    },
    ...{
      beforeDispatch: async (id: string) => {
        assert.equal(id, 'task_1:1');
        events.push('gate');
        controller.abort();
      },
    },
  });
  await assert.rejects(model.generate(request, controller.signal), /model_unavailable/);
  assert.deepEqual(events, ['count', 'gate']);
});

test('有效用量先等待独立收据，即使答案非法；收据故障不能交付结果', async () => {
  let saved = 0;
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json({ ...wireReply, choices: [] }),
    ...{
      onUsage: async (id: string, usage: unknown) => {
        assert.equal(id, 'task_1:1');
        assert.deepEqual(usage, { inputTokens: 100, outputTokens: 40 });
        saved++;
        throw new Error('测试收据故障');
      },
    },
  });
  await assert.rejects(model.generate(request, signal()), /model_unavailable/);
  assert.equal(saved, 1);
});

test('取消结果等待不取消已经启动的收据保存', async () => {
  const controller = new AbortController();
  const background: Promise<void>[] = [];
  let drained = false;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let saved = false;
  let began!: () => void;
  const started = new Promise<void>((resolve) => {
    began = resolve;
  });
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json(wireReply),
    ...{
      waitUntil: (pending: Promise<void>) => {
        background.push(pending);
        void pending.then(() => {
          drained = true;
        });
      },
      onUsage: async () => {
        began();
        await gate;
        saved = true;
      },
    },
  });
  const pending = model.generate(request, controller.signal);
  // 未实现钩子时立即交付结果；这个竞争直接暴露错误，避免测试挂起。
  assert.equal(
    await Promise.race([started.then(() => 'receipt'), pending.then(() => 'result')]),
    'receipt',
  );
  controller.abort();
  await assert.rejects(pending);
  assert.equal(background.length, 1);
  assert.equal(drained, false);
  release();
  await Promise.all(background);
  assert.equal(drained, true);
  assert.equal(saved, true);
});
const respond = (reply: unknown) =>
  createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json(reply),
  });
const toolsReply = (calls: unknown[]) => ({
  ...wireReply,
  choices: [
    {
      index: 0,
      finish_reason: 'tool_calls',
      message: {
        role: 'assistant',
        content: null,
        tool_calls: calls,
      },
    },
  ],
});
const call = (name = 'runs_summary', args = '{"runId":"run_1"}') => ({
  id: 'call_1',
  type: 'function',
  function: { name, arguments: args },
});

test('工具请求、返回和再次请求保持调用 ID 与只读工具参数', async () => {
  const first = await respond(toolsReply([call()])).generate(request, signal());
  assert.deepEqual(first.decision, {
    kind: 'tools',
    calls: [{ id: 'call_1', name: 'runs.summary', args: { runId: 'run_1' } }],
  });
  let sentBody = '';
  let countedBody = '';
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async (body) => {
      countedBody = body;
      return 100;
    },
    fetch: async (_, init) => {
      sentBody = init!.body as string;
      return Response.json(wireReply);
    },
  });
  await model.generate(
    {
      ...request,
      messages: [
        ...request.messages,
        {
          role: 'assistant',
          calls: [{ id: 'call_1', name: 'runs.summary', args: { runId: 'run_1' } }],
        },
        {
          role: 'tool',
          callId: 'call_1',
          name: 'runs.summary',
          data: { run: { runId: 'run_1' } },
        },
      ],
    },
    signal(),
  );
  assert.equal(countedBody, sentBody);
  assert.deepEqual(JSON.parse(sentBody).messages.slice(2), [
    { role: 'assistant', content: null, tool_calls: [call()] },
    {
      role: 'tool',
      tool_call_id: 'call_1',
      content: '{"run":{"runId":"run_1"}}',
    },
  ]);
});

test('拒绝非法工具、混合结果和截断输出，但保留已知账单用量', async () => {
  const malformed = [
    toolsReply([call('delete_all')]),
    toolsReply([call('runs_summary', '{')]),
    toolsReply([call('runs_summary', '{"runId":"run_1","extra":true}')]),
    toolsReply([call(), call()]),
    toolsReply([]),
    toolsReply(Array(7).fill(call())),
    { ...wireReply, choices: [] },
    { ...wireReply, choices: [wireReply.choices[0], wireReply.choices[0]] },
    ...['length', 'content_filter', 'aborted'].map((finish_reason) => ({
      ...wireReply,
      choices: [{ ...wireReply.choices[0], finish_reason }],
    })),
    ...[
      { role: 'tool', content: '错误身份' },
      { role: 'assistant', content: '   ' },
      { role: 'assistant', content: '长'.repeat(4001) },
      {
        role: 'assistant',
        content: '正常',
        reasoning_content: '不该暴露的推理',
      },
      { role: 'assistant', content: '正常', tool_calls: [call()] },
    ].map((message) => ({
      ...wireReply,
      choices: [{ index: 0, finish_reason: 'stop', message }],
    })),
  ];
  for (const reply of malformed) {
    const result = await respond(reply).generate(request, signal());
    assert.equal(result.decision, null);
    assert.deepEqual(result.usage, { inputTokens: 100, outputTokens: 40 });
  }
});

test('供应商用量缺失或矛盾不默认成零；超预算实际用量交回核心处理', async () => {
  for (const usage of [
    null,
    {},
    { prompt_tokens: 100, completion_tokens: 40 },
    { ...wireReply.usage, total_tokens: 0 },
    { ...wireReply.usage, prompt_tokens: -1 },
    { ...wireReply.usage, completion_tokens: 1.5 },
    { ...wireReply.usage, prompt_cache_hit_tokens: 1 },
    { ...wireReply.usage, prompt_tokens_details: { cached_tokens: 1 } },
  ]) {
    const result = await respond({ ...wireReply, usage }).generate(request, signal());
    assert.equal(result.usage, null);
  }
  const result = await respond({
    ...wireReply,
    usage: {
      prompt_tokens: 21000,
      completion_tokens: 601,
      total_tokens: 21601,
    },
  }).generate(request, signal());
  assert.deepEqual(result.usage, { inputTokens: 21000, outputTokens: 601 });
});

test('输入计数不可用、超限、非法上限和过大请求均在网络前拒绝', async () => {
  let calls = 0;
  for (const count of [0, -1, 20001, 1.5, NaN, Infinity]) {
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async () => count,
      fetch: async () => {
        calls++;
        return Response.json(wireReply);
      },
    });
    await assert.rejects(model.generate(request, signal()), /model_unavailable/);
  }
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      calls++;
      return Response.json(wireReply);
    },
  });
  for (const limits of [
    { maxInputTokens: 20001, maxOutputTokens: 600 },
    { maxInputTokens: 20000, maxOutputTokens: 601 },
    { maxInputTokens: 0, maxOutputTokens: 1 },
  ]) {
    await assert.rejects(model.generate({ ...request, limits }, signal()), /model_unavailable/);
  }
  await assert.rejects(
    model.generate({ ...request, instructions: '大'.repeat(30000) }, signal()),
    /model_unavailable/,
  );
  const failedCounter = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => {
      throw new Error('不应泄露的计数器细节');
    },
    fetch: async () => {
      calls++;
      return Response.json(wireReply);
    },
  });
  await assert.rejects(failedCounter.generate(request, signal()), /^Error: model_unavailable$/);
  assert.equal(calls, 0);
});

test('HTTP 错误不重试、不泄露响应或密钥；重定向不可转发凭据', async () => {
  let calls = 0;
  for (const status of [302, 401, 429, 500]) {
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async () => 100,
      fetch: async (_, init) => {
        calls++;
        assert.equal(init!.redirect, 'error');
        return new Response('不应暴露的响应', { status });
      },
    });
    await assert.rejects(model.generate(request, signal()), /^Error: model_unavailable$/);
  }
  assert.equal(calls, 4);
  for (const apiKey of ['', ' ', 'bad\r\nkey']) {
    assert.throws(
      () => createDeepseekModel({ apiKey, countInputTokens: async () => 100 }),
      /model_unavailable/,
    );
  }
});

test('取消输入计数不发送 HTTP，取消网络等待会传递中止', async () => {
  const abort = new AbortController();
  let calls = 0;
  const preflight = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: () => new Promise(() => {}),
    fetch: async () => {
      calls++;
      return Response.json(wireReply);
    },
  });
  const pending = preflight.generate(request, abort.signal);
  abort.abort();
  await assert.rejects(pending, /model_unavailable/);
  assert.equal(calls, 0);
  const networkAbort = new AbortController();
  let transmitted: AbortSignal | null | undefined;
  let markDispatched!: () => void;
  const dispatched = new Promise<void>((resolve) => {
    markDispatched = resolve;
  });
  const network = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: (_, init) => {
      transmitted = init!.signal;
      markDispatched();
      return new Promise(() => {});
    },
  });
  const inFlight = network.generate(request, networkAbort.signal);
  await dispatched;
  networkAbort.abort();
  await assert.rejects(inFlight, /model_unavailable/);
  assert.equal(transmitted!.aborted, true);
});

test('响应体超过 128 KiB、非法 JSON 或空流失败且停止继续读取', async () => {
  let cancelled = false;
  let emitted = false;
  const stream = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        if (emitted) controller.close();
        else {
          emitted = true;
          controller.enqueue(new Uint8Array(128 * 1024 + 1));
        }
      },
      cancel() {
        cancelled = true;
      },
    },
    { highWaterMark: 0 },
  );
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => new Response(stream),
  });
  await assert.rejects(model.generate(request, signal()), /model_unavailable/);
  assert.equal(cancelled, true);
  for (const response of [new Response('{'), new Response(null)]) {
    const broken = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async () => 100,
      fetch: async () => response,
    });
    await assert.rejects(broken.generate(request, signal()), /model_unavailable/);
  }
});

test('读取期间取消和迟到 HTTP 回包都会关闭正文', async () => {
  for (const late of [false, true]) {
    const abort = new AbortController();
    let cancelled = false;
    let returnResponse!: (response: Response) => void;
    let notifyFetch!: () => void;
    let notifyRead!: () => void;
    const fetching = new Promise<void>((resolve) => {
      notifyFetch = resolve;
    });
    const reading = new Promise<void>((resolve) => {
      notifyRead = resolve;
    });
    const body = new ReadableStream<Uint8Array>(
      {
        pull() {
          notifyRead();
        },
        cancel() {
          cancelled = true;
        },
      },
      { highWaterMark: 0 },
    );
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async () => 100,
      fetch: () => {
        notifyFetch();
        return new Promise<Response>((resolve) => {
          returnResponse = resolve;
        });
      },
    });
    const pending = model.generate(request, abort.signal);
    await fetching;
    if (!late) {
      returnResponse(new Response(body));
      await reading;
    }
    abort.abort();
    await assert.rejects(pending, /model_unavailable/);
    if (late) {
      returnResponse(new Response(body));
      await new Promise<void>((resolve) => setImmediate(resolve));
    }
    assert.equal(cancelled, true);
  }
});

test('30 秒总截止也覆盖不响应中止的外部计数器', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let calls = 0;
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: () => new Promise(() => {}),
    fetch: async () => {
      calls++;
      return Response.json(wireReply);
    },
  });
  const pending = model.generate(request, signal());
  t.mock.timers.tick(30000);
  await assert.rejects(pending, /model_unavailable/);
  assert.equal(calls, 0);
});
