import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 复用已锁定官方资产及 Python 独立参考，只保存六条虚构生产形状输入。
const here = name => fileURLToPath(new URL(name, import.meta.url));
const bytes = readFileSync(here('../token-count/.assets/tokenizer.json'));
const lock = JSON.parse(readFileSync(here('../token-count/assets.lock.json'), 'utf8'));
assert.equal(bytes.length, lock.assets['tokenizer.json'].bytes);
assert.equal(createHash('sha256').update(bytes).digest('hex'), lock.assets['tokenizer.json'].sha256);
const cases = JSON.parse(execFileSync(here('../token-count/.venv/Scripts/python.exe'),
  ['-B', here('../workers-spike/reference-cases.py')], { encoding:'utf8', maxBuffer:8*1024*1024,
    timeout:30000 })).filter(item => /^(production|loop-large)-round-/.test(item.name));
assert.equal(cases.length, 6);
const path = here('./.output/tokenizer-cases.json');
const output = JSON.stringify(cases, null, 2);
if (existsSync(path)) assert.equal(readFileSync(path, 'utf8'), output, '既有参考资产不一致，停止覆盖');
else writeFileSync(path, output, { flag:'wx' });
console.log(JSON.stringify(cases.map(({name,inputTokens,requestBytes,tokenIdsSha256}) =>
  ({ name,inputTokens,requestBytes,tokenIdsSha256 }))));
