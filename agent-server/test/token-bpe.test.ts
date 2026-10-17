import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { referenceCases, referenceRequests, tokenIdsHash, sha256 } from './token-reference.ts';
import { Tokenizer } from '@huggingface/tokenizers';

const bytes = readFileSync(new URL('../tools/token-count/.assets/tokenizer.json', import.meta.url));
const data = JSON.parse(bytes.toString('utf8'));
const golden = JSON.parse(readFileSync(new URL('../tools/token-count/golden.json', import.meta.url), 'utf8'));

test('生产计数器校验词表字节/hash，不接受损坏或其他资源', async () => {
  await assert.rejects(createInputTokenCounter(new Uint8Array()), { message: '输入计数不可用' });
  const damaged = Uint8Array.from(bytes);
  damaged[damaged.length - 1] = damaged[damaged.length - 1]! ^ 1;
  await assert.rejects(createInputTokenCounter(damaged), { message: '输入计数不可用' });
});

test('7 组冻结 golden 的生产计数与完整 JS token ID 均正确', async () => {
  const count = await createInputTokenCounter(bytes);
  const tokenizer = new Tokenizer(data, {});
  for (const item of golden) {
    const raw = JSON.stringify(item.request);
    assert.equal(await count(raw), item.expected.inputTokens, item.name);
    const tokens = tokenizer.tokenize(renderInputPrompt(raw), { add_special_tokens: false });
    const ids = tokens.map(token => {
      const id = tokenizer.token_to_id(token);
      assert.notEqual(id, undefined);
      return id!;
    });
    assert.equal(tokenIdsHash(ids), item.expected.tokenIdsSha256, item.name);
  }
});

test('146 组请求的实际模板、完整 ID 和生产数量均匹配独立参考', async () => {
  const count = await createInputTokenCounter(bytes);
  const tokenizer = new Tokenizer(data, {});
  for (const item of referenceCases()) {
    const raw = JSON.stringify(item.request);
    const prompt = renderInputPrompt(raw);
    assert.equal(sha256(prompt), sha256(item.prompt), item.name);
    assert.equal(tokenIdsHash(tokenizer.encode(prompt, { add_special_tokens: false }).ids), item.tokenIdsSha256, item.name);
    assert.equal(await count(raw), item.inputTokens, item.name);
  }
});

test('新种子 Unicode、added tokens 和逐字变化不靠旧固定样本计数', async () => {
  let seed = 20260916;
  const next = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed; };
  const texts: string[] = ['<image>这是文字</image>', 'a', 'ab', 'abc', '流', '流萤', '流萤🏃', '\u0000\u000b\u2028\u2029'];
  for (let index = 0; index < 128; index++) {
    let value = '';
    for (let part = 0; part < 80; part++) {
      const scalar = next() % 0x110000;
      if (scalar < 0xd800 || scalar > 0xdfff) value += String.fromCodePoint(scalar);
    }
    texts.push(value);
  }
  for (const token of data.added_tokens as { content: string }[]) {
    if (!token.content.includes('<｜image｜>') && !token.content.includes('<｜deepseek_image｜>'))
      texts.push(`边界a${token.content}b${token.content.slice(0, -1)}`);
  }
  const raws = texts.map(text => {
    const request = structuredClone(golden[0].request);
    request.messages[1].content = text;
    return JSON.stringify(request);
  });
  const expected = referenceRequests(raws);
  const count = await createInputTokenCounter(bytes);
  const tokenizer = new Tokenizer(data, {});
  for (const [index, raw] of raws.entries()) {
    const prompt = renderInputPrompt(raw);
    assert.equal(sha256(prompt), sha256(expected[index]!.prompt), `模板 ${index}`);
    assert.equal(await count(raw), expected[index]!.inputTokens, `计数 ${index}`);
    assert.equal(tokenIdsHash(tokenizer.encode(prompt, { add_special_tokens: false }).ids), expected[index]!.tokenIdsSha256, `ID ${index}`);
  }
});
