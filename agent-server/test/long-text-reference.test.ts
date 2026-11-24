import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runAgent } from '../src/loop.ts';
import { createDeepseekModel } from '../src/deepseek.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { referenceRequests, sha256 } from './token-reference.ts';
import type { BudgetPort } from '../src/contracts.ts';

test('冷热点长文本夹具由真实请求和独立 Python 参考锁定，不使用旧提示词的计数', async () => {
  const { longPrompts, longPromptReferences } = await import(new URL('../runtime-test/long-text-fixture.mjs', import.meta.url).href) as {
    longPrompts: string[]; longPromptReferences: { inputTokens: number; promptSha256: string }[];
  };
  const count = await createInputTokenCounter(readFileSync(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url)));
  const requests: string[] = [];
  const measured: number[] = [];
  const budget: BudgetPort = { async reserve(r) { return { reservationId: r.requestId }; }, async settle() {} };
  const model = createDeepseekModel({ apiKey: 'fixture-only-not-a-secret',
    async countInputTokens(raw) { requests.push(raw); const value = await count(raw); measured.push(value); return value; },
    async fetch(url, init) {
      assert.equal(url, 'https://api.deepseek.com/chat/completions');
      assert.equal(init?.body, requests.at(-1));
      return Response.json({ choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '合成长文本回答' } }],
        usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } });
    },
  });
  for (const prompt of longPrompts) {
    const result = await runAgent({ taskId: 'synthetic-long', prompt,
      consent: { cloudProcessing: true, runSummaries: true }, summaries: [{ runId: 'run_1', distanceMeters: 4000,
        durationSeconds: 1440, averagePaceSecPerKm: 360, averageHeartRate: 150 }] }, { model, budget });
    assert.equal(result.status, 'completed');
  }
  assert.equal(requests.length, longPrompts.length);
  const reference = referenceRequests(requests);
  assert.deepEqual(reference.map(row => ({ inputTokens: row.inputTokens, promptSha256: sha256(row.prompt) })), longPromptReferences);
  for (const [index, row] of reference.entries()) {
    assert.equal(renderInputPrompt(requests[index]!), row.prompt);
    assert.equal(measured[index], row.inputTokens);
  }
});
