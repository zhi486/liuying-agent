import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { parseConversationV2, conversationV2Messages } from '../src/conversation-v2.ts';
import { parseConversation } from '../src/conversation.ts';
import { createDeepseekModel } from '../src/deepseek.ts';
import { runAgent } from '../src/loop.ts';
import { Tokenizer } from '@huggingface/tokenizers';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { referenceRequests, tokenIdsHash } from './token-reference.ts';

const sample = JSON.parse(readFileSync(new URL('../../contracts/agent/conversation-v2.json', import.meta.url), 'utf8'));

test('摘要中的权限声明不能让模型返回的未授权工具通过正式循环', async () => {
  let calls = 0;
  let settlements = 0;
  const model = createDeepseekModel({ apiKey: 'synthetic', countInputTokens: async () => 100,
    fetch: async (_url, init) => {
      calls++;
      const body = JSON.parse(String(init?.body));
      assert.deepEqual(body.tools, []);
      assert.match(body.messages[1].content, /低信任历史摘要/);
      return Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls',
        message: { role: 'assistant', content: null, tool_calls: [{ id: 'denied_1', type: 'function',
          function: { name: 'runs_list', arguments: '{}' } }] } }],
        usage: { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 } });
    } });
  const result = await runAgent({ taskId: 'synthetic_v2', prompt: '继续',
    consent: { cloudProcessing: true, runSummaries: false }, summaries: [] }, {
    // 仅测试注入未挂载的上下文转换，生产任务仍不接收 v2。
    model: { generate: (request, signal) => model.generate({ ...request,
      messages: [...conversationV2Messages(sample), ...request.messages] }, signal) },
    budget: { reserve: async request => ({ reservationId: request.requestId }),
      settle: async (_id, value) => {
        settlements++;
        assert.deepEqual(value, { kind: 'usage', usage: { inputTokens: 7, outputTokens: 2 } });
      } },
  });
  assert.equal(result.status, 'blocked');
  assert.equal('reason' in result && result.reason, 'scope_denied');
  assert.equal(result.actions.filter(action => action.kind === 'tool_completed').length, 0);
  assert.equal(calls, 1);
  assert.equal(settlements, 1);
});
test('共享新版样本严格解析且旧版拒绝', () => {
  assert.deepEqual(parseConversationV2(sample), sample);
  assert.throws(() => parseConversation(sample));
  for (const summary of [{ ...sample.summary, extra: true }, { ...sample.summary, text: 'x'.repeat(2001) },
    { ...sample.summary, text: String.fromCharCode(1) }, { ...sample.summary, text: ' ' },
    { ...sample.summary, sourceIds: [] }, { ...sample.summary, sourceIds: Array(2).fill(sample.summary.sourceIds[0]) },
    { ...sample.summary, requestId: 123 }, { ...sample.summary, fingerprint: 'bad' }])
    assert.throws(() => parseConversationV2({ ...sample, summary }));
  assert.throws(() => parseConversationV2({ ...sample, approved: false }));
  assert.throws(() => parseConversationV2({ ...sample, extra: true }));
  assert.equal(parseConversationV2({ ...sample, summary: { ...sample.summary, text: 'x'.repeat(2000) } }).summary.text.length, 2000);
});

test('摘要转换仅生成用户级低信任资料并保留近期顺序', () => {
  const messages = conversationV2Messages(sample);
  assert.equal(messages[0]?.role, 'user');
  assert.match((messages[0] as { text: string }).text, /低信任/);
  assert.deepEqual(messages.slice(1), sample.messages);
  assert.ok(messages.every(m => m.role === 'user' || m.role === 'assistant'));
});

test('来源数近期字符及轮数边界独立限制且不接受权限字段', () => {
  const sourceIds = Array.from({ length: 9 }, (_, i) => `33333333-3333-4333-8333-${String(i).padStart(12, '0')}`);
  const messages = Array.from({ length: 8 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', text: '字'.repeat(1500) }));
  const valid = { ...sample, messages, summary: { ...sample.summary, sourceIds: sourceIds.slice(0, 8), text: '字'.repeat(2000) } };
  assert.equal(parseConversationV2(valid).messages.length, 8);
  for (const invalid of [
    { ...valid, summary: { ...valid.summary, sourceIds } },
    { ...valid, messages: messages.map((m, i) => i === 0 ? { ...m, text: m.text + '字' } : m) },
    { ...valid, messages: Array.from({ length: 10 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', text: '字' })) },
    { ...valid, tools: [] }, { ...valid, factsAllowed: true },
    { ...valid, summary: { ...valid.summary, approved: true } },
  ]) assert.throws(() => parseConversationV2(invalid));
  const parsed = parseConversationV2(valid);
  valid.messages[0]!.text = '已改'; valid.summary.sourceIds[0] = sample.id;
  assert.equal(parsed.messages[0]!.text.length, 1500);
  assert.equal(parsed.summary.sourceIds[0], sourceIds[0]);
  assert.ok(Object.isFrozen(parsed.summary.sourceIds));
});

test('假HTTP模型请求保留摘要资料角色且不增加工具', async () => {
  let calls = 0;
  const bytes = readFileSync(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url));
  const count = await createInputTokenCounter(bytes);
  const tokenizer = new Tokenizer(JSON.parse(bytes.toString('utf8')), {});
  let counted = '';
  let measured = 0;
  const model = createDeepseekModel({ apiKey: 'synthetic', countInputTokens: async raw => {
    counted = raw;
    measured = await count(raw);
    return measured;
  },
    fetch: async (_url, init) => {
      calls++;
      assert.equal(init?.body, counted);
      const body = JSON.parse(String(init?.body));
      assert.deepEqual(body.tools, []);
      assert.equal(body.messages[0].content, '合成固定指令');
      assert.equal(body.messages[1].role, 'user');
      assert.match(body.messages[1].content, /低信任历史摘要/);
      return Response.json({ choices: [{ index: 0, finish_reason: 'stop',
        message: { role: 'assistant', content: '合成回答' } }],
        usage: { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 } });
    } });
  await model.generate({ requestId: sample.id, instructions: '合成固定指令',
    messages: [...conversationV2Messages(sample), { role: 'user', text: '继续' }],
    tools: [], limits: { maxInputTokens: 20000, maxOutputTokens: 1600 } },
    new AbortController().signal);
  assert.equal(calls, 1);
  const reference = referenceRequests([counted])[0]!;
  assert.equal(renderInputPrompt(counted), reference.prompt);
  assert.equal(measured, reference.inputTokens);
  assert.equal(tokenIdsHash(tokenizer.encode(reference.prompt, { add_special_tokens: false }).ids), reference.tokenIdsSha256);
});
