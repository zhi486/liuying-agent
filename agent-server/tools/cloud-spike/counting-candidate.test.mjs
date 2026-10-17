import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import { candidateTokens } from './counting-candidate.mjs';

// 漏特殊标记、Unicode处理或预分词，必须与独立Python/Rust参考不一致。
test('轻量候选的146组完整token ID与锁定官方参考一致', () => {
  const here = name => fileURLToPath(new URL(name, import.meta.url));
  const bytes = readFileSync(here('../token-count/.assets/tokenizer.json'));
  const lock = JSON.parse(readFileSync(here('../token-count/assets.lock.json'),'utf8'));
  assert.equal(createHash('sha256').update(bytes).digest('hex'),lock.assets['tokenizer.json'].sha256);
  const cases = JSON.parse(execFileSync(here('../token-count/.venv/Scripts/python.exe'),
    ['-B',here('../workers-spike/reference-cases.py')], { encoding:'utf8',maxBuffer:8*1024*1024,timeout:30000 }));
  assert.equal(cases.length,146);
  const tokenizer = new Tokenizer(JSON.parse(bytes.toString('utf8')),{});
  for (const item of cases) {
    const tokens = candidateTokens(tokenizer,item.prompt);
    assert.equal(tokens.length,item.inputTokens,item.name);
    // 此映射只用于固定词表的差分验证，不放进性能计数路径。
    const ids = tokens.map(token => tokenizer.token_to_id(token));
    const encoded = Buffer.alloc(ids.length*4);
    ids.forEach((id,index) => {
      assert.ok(Number.isSafeInteger(id) && id >= 0,item.name);
      encoded.writeUInt32LE(id,index*4);
    });
    assert.equal(createHash('sha256').update(encoded).digest('hex'),item.tokenIdsSha256,item.name);
  }
});
