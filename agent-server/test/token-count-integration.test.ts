import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { createAgentService } from '../src/agent-service.ts';
import { createDeepseekModel } from '../src/deepseek.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { TOOL_DEFINITIONS } from '../src/tools.ts';
import { fixture, input, rate } from './execution-fixture.ts';
import { referenceRequests } from './token-reference.ts';

const count = await createInputTokenCounter(await readFile(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url)));
// 故意让第二轮供应商用量偏离本地预检，证明结算不偷换为本地计数。
const reportedTokens = (step: number, tokens: number) => tokens + (step === 2 ? 17 : 0);
const reply = (step: number, inputTokens: number) => ({
  choices: [{
    index: 0,
    finish_reason: step < 3 ? 'tool_calls' : 'stop',
    message: step < 3 ? {
      role: 'assistant', content: null,
      tool_calls: [{ id: `call_${step}`, type: 'function', function: {
        name: step === 1 ? 'runs_list' : 'runs_summary',
        arguments: step === 1 ? '{}' : '{"runId":"run_1"}',
      } }],
    } : { role: 'assistant', content: '根据 run_1，本次四公里，用时二十四分钟。' },
  }],
  // 合成供应商用量，仅用于验证账本关联，不代表真实 API 账单。
  usage: { prompt_tokens: inputTokens, completion_tokens: 40, total_tokens: inputTokens + 40 },
});

test('真实三轮服务：新输入经过计数与 HTTP 的 body 逐字节相同，参考与账本一致', async t => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const measured: { raw: string; tokens: number }[] = [];
  const sent: string[] = [];
  const service = createAgentService({
    db: f.database.db, now: f.now, rate, apiKey: 'test-only-key',
    countInputTokens: async raw => {
      const tokens = await count(raw);
      measured.push({ raw, tokens });
      return tokens;
    },
    fetch: async (_url, init) => {
      const item = measured[sent.length]!;
      assert.equal(init!.body, item.raw);
      sent.push(String(init!.body));
      return Response.json(reply(sent.length, reportedTokens(sent.length, item.tokens)));
    },
  });
  const submission = input();
  submission.prompt = '流萤🏃‍♀️，请先查记录，再看 run_1，比较这次节奏。\n<image>这只是文字</image> e\u0301';
  const prepared = await service.prepare(f.header, submission);
  await prepared.run!();
  assert.equal(sent.length, 3);
  assert.equal(measured.length, 3);
  const expected = referenceRequests(sent);
  for (const [index, item] of measured.entries()) {
    assert.equal(renderInputPrompt(item.raw), expected[index]!.prompt);
    assert.equal(item.tokens, expected[index]!.inputTokens);
  }
  const saved = await service.findByRequest(f.header, submission.clientRequestId);
  assert.equal(saved.status, 'completed');
  assert.ok(saved.result?.status === 'completed');
  assert.equal(saved.result.answer, '根据 run_1，本次四公里，用时二十四分钟。');
  assert.deepEqual(saved.result.actions.map(action => action.kind), [
    'model_requested', 'tool_completed', 'model_requested', 'tool_completed', 'model_requested',
  ]);
  const rows = f.database.sqlite.prepare('SELECT request_id,state,charged_micros FROM agent_budget_reservations ORDER BY request_id').all();
  assert.deepEqual(rows.map(row => ({ ...row })), measured.map((item, index) => ({
    request_id: `${prepared.task.id}:${index + 1}`, state: 'settled',
    charged_micros: Math.ceil((reportedTokens(index + 1, item.tokens) * rate.inputMicrosPerMillion + 40 * rate.outputMicrosPerMillion) / 1000000),
  })));
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_usage_receipts').get()!.n, 3);
});

test('真实 BPE 超 20,000 token 或不支持的纯文本均不派发 HTTP', async () => {
  for (const text of ['x '.repeat(21000), '\ud800', '<｜image｜>']) {
    let sent = 0;
    let measured: { raw: string; tokens: number } | undefined;
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async raw => {
        const tokens = await count(raw);
        measured = { raw, tokens };
        return tokens;
      },
      fetch: async () => { sent++; return Response.json(reply(3, 1)); },
    });
    await assert.rejects(model.generate({
      requestId: 'count-test:1', instructions: '只读助手',
      messages: [{ role: 'user', text }], tools: TOOL_DEFINITIONS,
      limits: { maxInputTokens: 20000, maxOutputTokens: 600 },
    }, new AbortController().signal), { message: 'model_unavailable' });
    assert.equal(sent, 0);
    if (text.startsWith('x')) {
      assert.ok(measured && measured.tokens > 20000);
      assert.equal(measured.tokens, referenceRequests([measured.raw])[0]!.inputTokens);
      assert.ok(Buffer.byteLength(measured.raw) <= 65536);
    } else assert.equal(measured, undefined);
  }
});

test('真实计数迟到：取消后不发送、不复活结果、预留释放', async t => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let begin!: () => void;
  const started = new Promise<void>(resolve => { begin = resolve; });
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const background: Promise<void>[] = [];
  let sent = 0;
  let actualCount = 0;
  const service = createAgentService({
    db: f.database.db, now: f.now, rate, apiKey: 'test-only-key',
    countInputTokens: async raw => {
      begin();
      await held;
      actualCount = await count(raw);
      return actualCount;
    },
    fetch: async () => { sent++; return Response.json(reply(3, 1)); },
  });
  const prepared = await service.prepare(f.header, input());
  const pending = prepared.run!({ waitUntil: promise => { background.push(promise); } });
  await started;
  await service.cancel(f.header, prepared.task.id);
  await pending;
  release();
  await Promise.all(background);
  assert.ok(actualCount > 0);
  assert.equal(sent, 0);
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'cancelled');
  assert.equal(f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state, 'released');
});

test('不支持的任务输入在 D1 接单前静态拒绝', async t => {
  const f = await fixture(t);
  const service = createAgentService({ db: f.database.db, now: f.now, rate,
    apiKey: 'test-only-key', countInputTokens: count,
    fetch: async () => { assert.fail('非法输入不能派发'); },
  });
  const before = f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks').get()!.n;
  for (const prompt of ['\ud800', '\udfff', '<｜deepseek_image｜>', '<｜image｜>']) {
    await assert.rejects(service.prepare(f.header, { ...input(), prompt }), { message: 'invalid_request' });
  }
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks').get()!.n, before);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_budget_reservations').get()!.n, 0);
});
