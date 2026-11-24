import { expect, it } from 'vitest';
import { createDeepseekModel } from '../src/deepseek.ts';
import { runAgent } from '../src/loop.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import tokenizerBytes from '../.generated/tokenizer.bin';

const event = (delta, finish = null) => `data: ${JSON.stringify({
  id: 'synthetic_completion', object: 'chat.completion.chunk',
  choices: [{ index: 0, delta, finish_reason: finish }],
  usage: finish ? { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } : null,
})}\n\n`;
const reply = body => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
const task = { taskId: 'runtime_stream', prompt: '我喜欢夜跑',
  consent: { cloudProcessing: true, runSummaries: false }, summaries: [] };
const budget = { async reserve(r) { return { reservationId: r.requestId }; }, async settle() {} };

it('workerd 真实 SSE 适配器和 BPE 在工具轮后接续增量回答，工具只执行一次', async () => {
  const count = await createInputTokenCounter(new Uint8Array(tokenizerBytes));
  const counted = [];
  const deltas = [];
  let fetched = 0;
  let executed = 0;
  let partialTool = false;
  const model = createDeepseekModel({ apiKey: 'fixture-only',
    countInputTokens: async raw => { counted.push(raw); return count(raw); },
    onTextDelta: async (id, text) => { deltas.push([id, text]); },
    fetch: async (_url, init) => {
      expect(init.body).toBe(counted[fetched++]);
      const wire = JSON.parse(init.body);
      expect(wire.stream).toBe(true);
      expect(wire.stream_options).toEqual({ include_usage: true });
      const first = fetched === 1;
      if (!first) {
        expect(executed).toBe(1);
        expect(JSON.parse(wire.messages.at(-1).content).status).toBe('found');
      }
      const pieces = first ? [
        event({ role: 'assistant', tool_calls: [{ index: 0, id: 'search_1', type: 'function',
          function: { name: 'memory_search', arguments: '{"query":' } }] }),
        event({ tool_calls: [{ index: 0, function: { arguments: '"夜跑"}' } }] }),
        event({}, 'tool_calls'), 'data: [DONE]\n\n',
      ] : [event({ role: 'assistant', content: '合成' }), event({ content: '回答' }), event({}, 'stop'), 'data: [DONE]\n\n'];
      return reply(new ReadableStream({ pull(c) {
        const next = pieces.shift();
        if (first && executed) partialTool = true;
        if (next === undefined) c.close(); else c.enqueue(new TextEncoder().encode(next));
      } }, { highWaterMark: 0 }));
    },
  });
  const result = await runAgent(task, { budget, model, memory: { async execute(call) {
    expect(call).toEqual({ id: 'search_1', name: 'memory.search', args: { query: '夜跑' } });
    executed++;
    return { status: 'found', items: [] };
  } } });
  expect(result.status).toBe('completed');
  expect(fetched).toBe(2); expect(executed).toBe(1); expect(partialTool).toBe(false);
  expect(deltas.map(row => row[1])).toEqual(['合成', '回答']);
  expect(new Set(deltas.map(row => row[0])).size).toBe(1);
});

it('workerd SSE 真正等待后续块时已有增量，取消后不再读取或执行工具', async () => {
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  let cancelled = false;
  let feed;
  const pieces = [];
  let calls = 0;
  const abort = new AbortController();
  const model = createDeepseekModel({ apiKey: 'fixture-only', countInputTokens: async () => 100,
    onTextDelta: async (_id, text) => { pieces.push(text); entered(); },
    fetch: async () => { calls++; return reply(new ReadableStream({ start(c) {
      feed = c;
      c.enqueue(new TextEncoder().encode(event({ role: 'assistant', content: '尚未结束' })));
    }, cancel() { cancelled = true; } })); },
  });
  const pending = runAgent(task, { budget, model }, { signal: abort.signal });
  await started;
  expect(pieces).toEqual(['尚未结束']);
  abort.abort();
  expect((await pending).status).toBe('cancelled');
  expect(cancelled).toBe(true); expect(calls).toBe(1);
  expect(() => feed.enqueue(new Uint8Array([1]))).toThrow();
});
