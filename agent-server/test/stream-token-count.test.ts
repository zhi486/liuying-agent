import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { parseCountingRequest } from '../src/token-count/request.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { referenceRequests } from './token-reference.ts';

const golden = JSON.parse(readFileSync(new URL('../tools/token-count/golden.json', import.meta.url), 'utf8'));

test('流式实际信封与 JSON 信封的模板、独立 Python token ID 和 BPE 计数一致', async () => {
  const count = await createInputTokenCounter(readFileSync(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url)));
  const raws: string[] = [];
  for (const row of golden) {
    raws.push(JSON.stringify(row.request), JSON.stringify({ ...row.request, stream: true, stream_options: { include_usage: true } }));
  }
  const refs = referenceRequests(raws);
  for (let i = 0; i < raws.length; i += 2) {
    assert.equal(parseCountingRequest(raws[i + 1]!).stream, true);
    assert.deepEqual(refs[i], refs[i + 1]);
    for (const index of [i, i + 1]) {
      assert.equal(renderInputPrompt(raws[index]!), refs[index]!.prompt);
      assert.equal(await count(raws[index]!), refs[index]!.inputTokens);
    }
  }
});

test('计数只接受明确启用用量的流式信封，不接受额外字段或真假值混淆', () => {
  for (const changed of [
    { stream: true }, { stream: false, stream_options: { include_usage: true } },
    { stream: 'true', stream_options: { include_usage: true } },
    { stream: true, stream_options: { include_usage: false } },
    { stream: true, stream_options: { include_usage: 1 } },
    { stream: true, stream_options: { include_usage: true, other: 1 } },
    { stream: true, stream_options: null },
  ]) assert.throws(() => parseCountingRequest(JSON.stringify({ ...golden[0].request, ...changed })),
    { message: '输入计数不可用' });
});
