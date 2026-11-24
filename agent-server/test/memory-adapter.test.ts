import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { Tokenizer } from '@huggingface/tokenizers';
import { createDeepseekModel } from '../src/deepseek.ts';
import { runAgent } from '../src/loop.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { parseCountingRequest } from '../src/token-count/request.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { referenceRequests, tokenIdsHash } from './token-reference.ts';
import { MEMORY_TOOL_DEFINITIONS } from '../src/tools.ts';
import type { AgentTask, BudgetPort, MemoryToolCall, MemoryToolPort } from '../src/contracts.ts';

const bytes = await readFile(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url));
const count = await createInputTokenCounter(bytes);
const tokenizer = new Tokenizer(JSON.parse(bytes.toString('utf8')), {});
const memoryId = '11111111-1111-4111-8111-111111111111';
const task: AgentTask = {
  taskId: 'memory_adapter', prompt: '我喜欢夜跑😊', consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
};
const budget: BudgetPort = { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} };
const search: MemoryToolCall = { id: 'search_1', name: 'memory.search', args: { query: '夜跑' } };
const save = (version?: number): MemoryToolCall => ({ id: 'save_1', name: 'memory.upsert', args: {
  category: 'preference', evidence: task.prompt, target: version ? { id: memoryId, version } : null,
} });

async function exercise(calls: MemoryToolCall[], input = task, factsAllowed = false) {
  const measured: { raw: string; tokens: number }[] = [];
  const sent: string[] = [];
  // 这里只替代原生宿主，不宣称经过 Room、设备网络或生产 DO。
  const memory: MemoryToolPort = { factsAllowed, async execute(call) {
    return call.name === 'memory.search' ? { status: 'found', items: [] } : {
      status: 'saved', memory: { id: memoryId, version: (call.args.target?.version ?? 0) + 1, content: call.args.evidence }, replayed: false,
    };
  } };
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async raw => {
      const tokens = await count(raw);
      measured.push({ raw, tokens });
      return tokens;
    },
    fetch: async (_url, init) => {
      assert.equal(init!.body, measured[sent.length]!.raw);
      sent.push(String(init!.body));
      const call = calls[sent.length - 1];
      return Response.json({ choices: [{ index: 0, finish_reason: call ? 'tool_calls' : 'stop', message: call ? {
        role: 'assistant', content: null, tool_calls: [{ id: call.id, type: 'function', function: {
          name: call.name.replace('.', '_'), arguments: JSON.stringify(call.args),
        } }],
      } : { role: 'assistant', content: '知道啦，偏好已经保存。' } }],
      usage: { prompt_tokens: measured.at(-1)!.tokens, completion_tokens: 20, total_tokens: measured.at(-1)!.tokens + 20 } });
    },
  });
  const result = await runAgent(input, { model, budget, memory });
  assert.equal(result.status, 'completed');
  assert.equal(sent.length, calls.length + 1);
  const references = referenceRequests(sent);
  for (const [index, item] of measured.entries()) {
    const prompt = renderInputPrompt(item.raw);
    assert.equal(prompt, references[index]!.prompt);
    assert.equal(item.tokens, references[index]!.inputTokens);
    assert.equal(tokenIdsHash(tokenizer.encode(prompt, { add_special_tokens: false }).ids), references[index]!.tokenIdsSha256);
  }
  return sent;
}

test('事实目录及往返经过正式计数器，每轮模板和 token ID 匹配独立参考', async () => {
  const evidence = '我的主要编程语言是Kotlin';
  const sent = await exercise([{ id: 'save_fact', name: 'memory.upsert', args: {
    category: 'fact', evidence, target: null,
  } }], { ...task, prompt: evidence }, true);
  const request = JSON.parse(sent[0]!);
  const definition = request.tools.find((tool: { function: { name: string } }) => tool.function.name === 'memory_upsert');
  assert.deepEqual(definition.function.parameters.properties.category.enum, ['preference', 'goal', 'fact']);
  const legacy = JSON.parse(sent[1]!);
  legacy.tools.find((tool: { function: { name: string } }) => tool.function.name === 'memory_upsert')
    .function.parameters = MEMORY_TOOL_DEFINITIONS[1].parameters;
  assert.throws(() => parseCountingRequest(JSON.stringify(legacy)));
  definition.function.parameters.properties.category.enum.push('inferred');
  assert.throws(() => parseCountingRequest(JSON.stringify(request)));
});

test('合成记忆保存与检索经过真实适配器：空目标参数、每轮模板和完整 token ID 匹配独立参考', async () => {
  const sent = await exercise([save(), search]);
  assert.ok(renderInputPrompt(sent[1]!).includes('name="target" string="false">null</'));
  assert.deepEqual(JSON.parse(sent[0]!).tools.map((tool: { function: { name: string } }) => tool.function.name), ['memory_search', 'memory_upsert']);
});

test('记忆保存和检索的实际请求将跑步限制标为内部边界，不要求普通聊天附带说明', async () => {
  const prompt = '我喜欢把测试计划排在周三';
  const sent = await exercise([{ id: 'save_1', name: 'memory.upsert', args: {
    category: 'preference', evidence: prompt, target: null,
  } }, search], { ...task, prompt });
  for (const raw of sent) {
    const request = JSON.parse(raw);
    const instructions = request.messages[0].content as string;
    assert.match(instructions, /以下是内部能力边界，不是每次回复的附加说明/);
    assert.match(instructions, /普通聊天、偏好保存和记忆检索不主动提及无关的跑步授权或工具限制/);
    assert.match(instructions, /只有问题涉及个人跑步数据时/);
    assert.match(instructions, /不要猜测数值或声称已读取记录/);
    assert.deepEqual(request.tools.map((tool: { function: { name: string } }) => tool.function.name), ['memory_search', 'memory_upsert']);
  }
});

test('新会话偏好追问实际请求带有先检索约定和短主题词用法，不预填上轮答案', async () => {
  const prompt = '我希望把测试计划安排在哪一天';
  const sent = await exercise([{ id: 'recall_1', name: 'memory.search', args: { query: '测试计划' } }],
    { ...task, taskId: 'new_memory_conversation', prompt });
  const request = JSON.parse(sent[0]!);
  const instructions = request.messages[0].content as string;
  assert.match(instructions, /用户询问自己的既有偏好、目标或以前告诉你的信息时，先调用 memory.search/);
  assert.match(instructions, /不能仅因当前消息没有重述就断言没有记忆/);
  assert.match(instructions, /回忆偏好不等于创建日程/);
  const definition = request.tools.find((tool: { function: { name: string } }) => tool.function.name === 'memory_search');
  assert.match(definition.function.description, /按原文子串匹配/);
  assert.match(definition.function.description, /短主题词/);
  assert.match(definition.function.description, /不要把整句问题/);
  assert.deepEqual(request.messages.filter((message: { role: string }) => message.role === 'user'), [{ role: 'user', content: prompt }]);
  assert.ok(!JSON.stringify(request).includes('周三'));
  assert.equal(request.tools.length, 2);
  assert.equal(JSON.parse(sent[1]!).messages.at(-2).tool_calls[0].function.arguments, '{"query":"测试计划"}');
});

test('自然聊天约定进入每轮真实适配器请求，检索回执和必要的能力说明仍原样保留', async () => {
  // 固定供应商回复只验证请求约定和传递链路，不能证明真实模型遵循效果。
  for (const recall of [true, false]) {
    const prompt = recall ? '我喜欢什么时段散步？' : '帮我在日历里创建明天的散步提醒';
    const answer = recall ? '你喜欢清晨散步，这是你之前保存的偏好。'
      : '我现在不能替你创建日历提醒，可以先帮你写好提醒内容。';
    const sent: { messages: { role: string; content: string }[];
      tools?: { function: { name: string } }[] }[] = [];
    const calls: MemoryToolCall[] = [];
    const model = createDeepseekModel({ apiKey: 'test-only-key', countInputTokens: count,
      fetch: async (_url, init) => {
        const request = JSON.parse(String(init!.body));
        sent.push(request);
        const firstSearch = recall && sent.length === 1;
        return Response.json({ choices: [{ index: 0, finish_reason: firstSearch ? 'tool_calls' : 'stop',
          message: firstSearch ? { role: 'assistant', content: null, tool_calls: [{ id: search.id, type: 'function',
            function: { name: 'memory_search', arguments: '{"query":"散步"}' } }] }
            : { role: 'assistant', content: answer } }],
          usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
      },
    });
    const found = { status: 'found', items: [{ id: memoryId, version: 1, content: '我喜欢清晨散步' }] };
    const result = await runAgent({ ...task, prompt }, { model, budget,
      ...(recall ? { memory: { async execute(call: MemoryToolCall) { calls.push(call); return found; } } } : {}),
    });
    assert.equal(result.status, 'completed');
    if (result.status === 'completed') assert.equal(result.answer, answer);
    assert.equal(sent.length, recall ? 2 : 1);
    for (const request of sent) {
      const instructions = request.messages[0]!.content;
      assert.match(instructions, /先直接回应本轮问题，像日常对话一样自然简洁/);
      assert.match(instructions, /仅当能力或授权限制实际阻碍本轮请求，或用户明确询问时，才简短解释/);
      assert.match(instructions, /不要例行附加未使用工具、余额用量或通用须知/);
      if (recall) {
        assert.match(instructions, /找到偏好后只回答相关偏好及简短来源，不附加日历或计划管理工具说明/);
        assert.match(instructions, /你更喜欢傍晚散步，这是你之前保存的偏好/);
      }
      assert.deepEqual(request.tools?.map(tool => tool.function.name), recall ? ['memory_search', 'memory_upsert'] : []);
    }
    assert.deepEqual(sent[0]!.messages.filter((message: { role: string }) => message.role === 'user'),
      [{ role: 'user', content: prompt }]);
    assert.ok(!JSON.stringify(sent[0]).includes('清晨'));
    if (recall) {
      assert.deepEqual(calls, [{ ...search, args: { query: '散步' } }]);
      assert.deepEqual(JSON.parse(sent[1]!.messages.at(-1)!.content), found);
    } else assert.deepEqual(calls, []);
  }
});

test('合成版本更新及四个工具并存：嵌套目标参数与数值 schema 不改变官方模板', async () => {
  const sent = await exercise([search, save(7)], { ...task,
    consent: { cloudProcessing: true, runSummaries: true }, summaries: [{
      runId: 'run_1', distanceMeters: 1000, durationSeconds: 360, averagePaceSecPerKm: 360, averageHeartRate: 140,
    }],
  });
  assert.equal(JSON.parse(sent[0]!).tools.length, 4);
  assert.ok(renderInputPrompt(sent[2]!).includes(`name="target" string="false">{"id": "${memoryId}", "version": 7}</`));
  const raw = JSON.parse(sent[2]!);
  raw.tools[2].function.parameters.additionalProperties = true;
  assert.throws(() => parseCountingRequest(JSON.stringify(raw)), { message: '输入计数不可用' });
  const invalid = JSON.parse(sent[2]!);
  invalid.messages.findLast((message: { role: string }) => message.role === 'assistant').tool_calls[0].function.arguments =
    JSON.stringify({ category: 'preference', evidence: task.prompt, target: { id: memoryId, version: 0 } });
  assert.throws(() => parseCountingRequest(JSON.stringify(invalid)), { message: '输入计数不可用' });
});
