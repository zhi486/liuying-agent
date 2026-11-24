import { expect, it } from 'vitest';
import { runAgent } from '../src/loop.ts';
import { createDeepseekModel } from '../src/deepseek.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { parseResult } from '../src/cloud-task-contracts.ts';
import tokenizerBytes from '../.generated/tokenizer.bin';

const task = () => ({ taskId: 'runtime_memory', prompt: '我喜欢夜跑',
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [] });
const memoryId = '11111111-1111-4111-8111-111111111111';
const save = { id: 'save_1', name: 'memory.upsert', args: { category: 'preference', evidence: '我喜欢夜跑', target: null } };
const final = { kind: 'final', text: '合成回答' };
const budget = { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} };

it('workerd 中真实适配器和 BPE 支持自主保存及新会话搜索，仅使用合成宿主', async () => {
  const counter = await createInputTokenCounter(new Uint8Array(tokenizerBytes));
  let stored;
  const memory = { async execute(call) {
    if (call.name === 'memory.search') return { status: 'found', items: stored ? [stored] : [] };
    stored = { id: memoryId, version: 1, content: call.args.evidence };
    return { status: 'saved', memory: stored, replayed: false };
  } };
  for (const operation of ['save', 'search']) {
    const sent = [];
    const counted = [];
    const model = createDeepseekModel({ apiKey: 'fixture-only',
      countInputTokens: async raw => { counted.push(raw); return counter(raw); },
      fetch: async (_url, init) => {
        expect(init.body).toBe(counted[sent.length]);
        const request = JSON.parse(init.body);
        sent.push(request);
        const call = operation === 'save' ? save : { id: 'search_1', name: 'memory.search', args: { query: '夜跑' } };
        return Response.json({ choices: [{ index: 0, finish_reason: sent.length === 1 ? 'tool_calls' : 'stop',
          message: sent.length === 1 ? { role: 'assistant', content: null, tool_calls: [{ id: call.id, type: 'function',
            function: { name: call.name.replace('.', '_'), arguments: JSON.stringify(call.args) } }] }
            : { role: 'assistant', content: '合成回答' } }],
          usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
      },
    });
    const input = operation === 'save' ? task() : { ...task(), taskId: 'new_conversation', prompt: '我喜欢什么时候跑步' };
    const result = await runAgent(input, { model, budget, memory });
    expect(result.status).toBe('completed');
    expect(parseResult(JSON.parse(JSON.stringify(result)))).toEqual(result);
    expect(sent).toHaveLength(2);
    expect(sent[0].messages.filter(message => message.role === 'user')).toEqual([{ role: 'user', content: input.prompt }]);
    expect(sent[0].tools.map(tool => tool.function.name)).toEqual(['memory_search', 'memory_upsert']);
    const observation = JSON.parse(sent[1].messages.at(-1).content);
    expect(observation).toEqual(operation === 'save' ? { status: 'saved', memory: stored, replayed: false } : { status: 'found', items: [stored] });
  }
});

it('workerd 中回执断连不补写，仍可回答普通问题', async () => {
  const requests = [];
  let attempts = 0;
  const decisions = [{ kind: 'tools', calls: [save] }, { kind: 'tools', calls: [{ ...save, id: 'save_2', args: {
    ...save.args, target: { id: memoryId, version: 1 },
  } }] }, final];
  const result = await runAgent(task(), {
    budget, memory: { async execute() { attempts++; throw new Error('合成回执断连'); } },
    model: { async generate(request) {
      requests.push(request);
      return { decision: decisions[requests.length - 1], usage: { inputTokens: 100, outputTokens: 20 } };
    } },
  });
  expect(result.status).toBe('completed');
  expect(attempts).toBe(1);
  for (const request of requests.slice(1)) expect(request.messages.at(-1).data).toEqual({ status: 'unconfirmed', reason: 'unavailable' });
});

it('workerd 真实适配器区分空检索、拒绝和未确认，不混入旧会话答案或自动写入', async () => {
  const counter = await createInputTokenCounter(new Uint8Array(tokenizerBytes));
  const cases = [
    [{ status: 'found', items: [] }, { status: 'found', items: [] }],
    [{ status: 'rejected', reason: 'consent_required' }, { status: 'rejected', reason: 'consent_required' }],
    [{ status: 'rejected', reason: 'storage_unavailable' }, { status: 'unconfirmed', reason: 'unavailable' }],
    [null, { status: 'unconfirmed', reason: 'invalid_response' }],
    ['throw', { status: 'unconfirmed', reason: 'unavailable' }],
  ];
  for (const [response, expected] of cases) {
    const sent = [];
    const executed = [];
    const prompt = '我希望把测试计划安排在哪一天';
    const call = { id: 'recall_1', name: 'memory.search', args: { query: '测试计划' } };
    const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: counter,
      fetch: async (_url, init) => {
        const request = JSON.parse(init.body);
        sent.push(request);
        const first = sent.length === 1;
        if (!first) {
          expect(request.messages.at(-1).role).toBe('tool');
          expect(request.messages.at(-1).tool_call_id).toBe(call.id);
          expect(JSON.parse(request.messages.at(-1).content)).toEqual(expected);
          expect(request.messages[0].content).toContain('拒绝或未确认则说明暂时无法读取');
        }
        return Response.json({ choices: [{ index: 0, finish_reason: first ? 'tool_calls' : 'stop',
          message: first ? { role: 'assistant', content: null, tool_calls: [{ id: call.id, type: 'function',
            function: { name: 'memory_search', arguments: JSON.stringify(call.args) } }] }
            : { role: 'assistant', content: '合成回复，仅验证状态传递。' } }],
          usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
      },
    });
    const result = await runAgent({ ...task(), taskId: 'isolated_recall', prompt }, { model, budget,
      memory: { async execute(actual) {
        executed.push(actual);
        if (response === 'throw') throw new Error('PRIVATE-MEMORY-TRANSPORT');
        return response;
      } },
    });
    expect(result.status).toBe('completed');
    expect(executed).toEqual([call]);
    expect(sent).toHaveLength(2);
    expect(sent[0].messages.filter(message => message.role === 'user')).toEqual([{ role: 'user', content: prompt }]);
    expect(JSON.stringify(sent)).not.toMatch(/周三|PRIVATE-MEMORY-TRANSPORT/);
    expect(result.actions).toEqual([{ kind: 'model_requested', step: 1 },
      { kind: 'tool_completed', step: 1, tool: 'memory.search' }, { kind: 'model_requested', step: 2 }]);
  }
});

it('workerd 中等待原生回执时取消，迟到保存不触发下一轮模型', async () => {
  let started;
  const entered = new Promise(resolve => { started = resolve; });
  let release;
  const held = new Promise(resolve => { release = resolve; });
  let calls = 0;
  let signal;
  const abort = new AbortController();
  const work = runAgent(task(), { budget,
    model: { async generate() { calls++; return { decision: { kind: 'tools', calls: [save] }, usage: { inputTokens: 100, outputTokens: 20 } }; } },
    memory: { async execute(_call, current) { signal = current; started(); return held; } },
  }, { signal: abort.signal });
  await entered;
  abort.abort();
  const result = await work;
  release({ status: 'saved', memory: { id: memoryId, version: 1, content: '我喜欢夜跑' }, replayed: false });
  await held;
  expect(result.status).toBe('cancelled');
  expect(signal.aborted).toBe(true);
  expect(calls).toBe(1);
  expect(result.actions).toEqual([{ kind: 'model_requested', step: 1 }]);
});

it('workerd 中记忆冲突后不以新建补写，同批和下一步仍可检索及回答', async () => {
  for (const sameBatch of [true, false]) {
    const edit = { ...save, id: 'stale_edit', args: { ...save.args, target: { id: memoryId, version: 1 } } };
    const lookup = { id: 'search_1', name: 'memory.search', args: { query: '夜跑' } };
    const decisions = sameBatch ? [{ kind: 'tools', calls: [edit, lookup, save] }, final]
      : [{ kind: 'tools', calls: [edit] }, { kind: 'tools', calls: [lookup, save] }, final];
    const executed = [];
    const requests = [];
    const memory = { async execute(call) {
      executed.push(call);
      return call.name === 'memory.search' ? { status: 'found', items: [] } : { status: 'rejected', reason: 'conflict' };
    } };
    const result = await runAgent(task(), { budget, memory,
      model: { async generate(request) {
        requests.push(request);
        return { decision: decisions[requests.length - 1], usage: { inputTokens: 100, outputTokens: 20 } };
      } },
    });
    expect(result.status).toBe('completed');
    expect(executed).toEqual([edit, lookup]);
    expect(requests.at(-1).messages.at(-1).data).toEqual({ status: 'rejected', reason: 'conflict' });
  }
});
