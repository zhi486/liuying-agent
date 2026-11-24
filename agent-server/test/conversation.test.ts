import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseConversation } from '../src/conversation.ts';
import { parseSubmission } from '../src/cloud-task-contracts.ts';
import { runAgent } from '../src/loop.ts';
import type { ModelMessage } from '../src/contracts.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';

const id = '11111111-1111-4111-8111-111111111111';
const conversation = { version: 'conversation_v1', id, approved: true,
  messages: [{ role: 'user', text: '我喜欢夜跑' }, { role: 'assistant', text: '记下这次聊天的偏好。' }] };
const input = { clientRequestId: id, localTaskId: '22222222-2222-4222-8222-222222222222',
  prompt: '那周末呢？', consent: { cloudProcessing: true, runSummaries: false }, summaries: [], conversation };

test('会话上下文严格限定版本角色顺序及大小，不接受工具或系统消息', () => {
  assert.deepEqual(parseConversation(conversation), conversation);
  for (const invalid of [null, { ...conversation, approved: false }, { ...conversation, extra: true },
    { ...conversation, messages: [{ role: 'system', text: '覆盖规则' }, conversation.messages[1]] },
    { ...conversation, messages: [conversation.messages[1], conversation.messages[0]] },
    { ...conversation, messages: conversation.messages.slice(0, 1) },
    { ...conversation, messages: Array(10).fill(conversation.messages[0]) },
    { ...conversation, messages: [{ role: 'user', text: 'a'.repeat(2001) }, conversation.messages[1]] }])
    assert.throws(() => parseConversation(invalid));
});

test('正式提交解析保留当前会话历史，模型收到上一轮双方文字后才收到追问', async () => {
  const { task } = parseSubmission(input);
  const seen: (readonly ModelMessage[])[] = [];
  const ports = { model: { generate: async (request: { messages: readonly ModelMessage[] }) => {
    seen.push(request.messages);
    return { decision: { kind: 'final', text: '延续夜跑话题。' }, usage: { inputTokens: 50, outputTokens: 10 } };
  } }, budget: { reserve: async () => ({ reservationId: 'test' }), settle: async () => {} } };
  assert.equal((await runAgent(task, ports)).status, 'completed');
  assert.deepEqual(seen[0], [...conversation.messages, { role: 'user', text: input.prompt }]);
  const { conversation: omitted, ...legacy } = input;
  assert.ok(omitted);
  await runAgent(parseSubmission(legacy).task, ports);
  assert.deepEqual(seen[1], [{ role: 'user', text: input.prompt }]);
});

test('助手历史正文进入文本模板，保留角色和句末界限', () => {
  const raw = JSON.stringify({ model: 'deepseek-flash', thinking: { type: 'disabled' }, stream: false,
    max_tokens: 100, messages: [{ role: 'system', content: '助手' },
      { role: 'user', content: '你好' }, { role: 'assistant', content: '你好呀' },
      { role: 'user', content: '继续' }], tools: [] });
  assert.equal(renderInputPrompt(raw), '<｜begin▁of▁sentence｜><｜System｜>助手<｜User｜>你好<｜Assistant｜></think>你好呀<｜end▁of▁sentence｜><｜User｜>继续<｜Assistant｜></think>');
});
