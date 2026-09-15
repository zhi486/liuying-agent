// 一次性 BPE 差分及 Node 耗时探针；不是生产计数器，也不移植消息模板。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Tokenizer } from '@huggingface/tokenizers';

const here = (name) => fileURLToPath(new URL(name, import.meta.url));
const reference = (name) => here(`../token-count/${name}`);
const cases = JSON.parse(
  execFileSync(reference('.venv/Scripts/python.exe'), ['-B', here('reference-cases.py')], {
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    timeout: 30000,
  }),
);
const lock = JSON.parse(readFileSync(reference('assets.lock.json'), 'utf8'));
const bytes = readFileSync(reference('.assets/tokenizer.json'));
assert.equal(bytes.length, lock.assets['tokenizer.json'].bytes);
assert.equal(
  createHash('sha256').update(bytes).digest('hex'),
  lock.assets['tokenizer.json'].sha256,
);
const cpuMs = (previous) => {
  const delta = process.cpuUsage(previous);
  return (delta.user + delta.system) / 1000;
};
const memoryMiB = () =>
  Object.fromEntries(
    Object.entries(process.memoryUsage()).map(([name, value]) => [
      name,
      +(value / 1048576).toFixed(2),
    ]),
  );
const beforeMemory = memoryMiB();
const initCpu = process.cpuUsage();
const initWall = performance.now();
// 不获取远程 tokenizer_config；纯 BPE 显式禁用额外特殊 token。
const tokenizer = new Tokenizer(JSON.parse(bytes.toString('utf8')), {});
const initialization = {
  wallMs: performance.now() - initWall,
  cpuMs: cpuMs(initCpu),
  beforeMemory,
  afterMemory: memoryMiB(),
};
const rows = [];
for (const item of cases) {
  const cpu = process.cpuUsage();
  const wall = performance.now();
  const encoded = tokenizer.encode(item.prompt, { add_special_tokens: false });
  const wallMs = performance.now() - wall;
  const usedCpuMs = cpuMs(cpu);
  const idsBytes = Buffer.alloc(encoded.ids.length * 4);
  encoded.ids.forEach((id, index) => idsBytes.writeUInt32LE(id, index * 4));
  const hash = createHash('sha256').update(idsBytes).digest('hex');
  rows.push({
    name: item.name,
    requestBytes: item.requestBytes,
    referenceTokens: item.inputTokens,
    actualTokens: encoded.ids.length,
    exactIds: hash === item.tokenIdsSha256,
    wallMs,
    cpuMs: usedCpuMs,
  });
}
const repeated = [];
for (const item of cases.filter((item) => /^(production|loop-large)-round-/.test(item.name))) {
  const times = [];
  for (let i = 0; i < 10; i++) {
    const start = performance.now();
    tokenizer.encode(item.prompt, { add_special_tokens: false });
    times.push(performance.now() - start);
  }
  repeated.push({
    name: item.name,
    minWallMs: Math.min(...times),
    maxWallMs: Math.max(...times),
    medianWallMs: times.sort((a, b) => a - b)[5],
  });
}
const mismatches = rows.filter((row) => !row.exactIds);
console.log(
  JSON.stringify(
    {
      assurance: 'disposable_node_bpe_spike_only',
      runtime: process.version,
      platform: process.platform,
      arch: process.arch,
      tokenizer: '@huggingface/tokenizers@0.2.0',
      initialization,
      total: rows.length,
      exact: rows.length - mismatches.length,
      mismatches,
      samples: rows.filter((row) => !row.name.startsWith('seeded-')),
      repeated,
      finalMemory: memoryMiB(),
      limitations: [
        '仅 BPE 对照，prompt 仍由 Python 生成',
        'Node 耗时/内存不等于 Workers CPU/内存',
        '未调用真实 API 或部署',
      ],
    },
    null,
    2,
  ),
);
if (mismatches.length) process.exitCode = 1;
