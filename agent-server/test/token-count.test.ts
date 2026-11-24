import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { parseCountingRequest } from '../src/token-count/request.ts';

const golden = JSON.parse(readFileSync(new URL('../tools/token-count/golden.json', import.meta.url), 'utf8'));
const plain = () => structuredClone(golden[0].request);
const toolHistory = () => structuredClone(golden[3].request);

test('计数协议接受固定样本以外的文字，不依赖请求哈希', () => {
  const request = plain();
  request.messages[1].content = '今天想慢跑十五分钟，👩🏽‍💻<image>不是图片</image>';
  assert.deepEqual(parseCountingRequest(JSON.stringify(request)), request);
});

test('底层计数允许空文字，不混入任务入口的非空和 2000 字符限制', () => {
  for (const text of ['', 'a'.repeat(3000)]) {
    const request = plain();
    request.messages[1].content = text;
    assert.deepEqual(parseCountingRequest(JSON.stringify(request)), request);
  }
});

test('精确计数接受 1600 token 输出上限的实际模型请求', () => {
  const request = plain();
  request.max_tokens = 1600;
  assert.deepEqual(parseCountingRequest(JSON.stringify(request)), request);
});

for (const [name, change] of [
  ['未知模型', (r: any) => { r.model = 'other'; }],
  ['thinking', (r: any) => { r.thinking.type = 'enabled'; }],
  ['stream', (r: any) => { r.stream = true; }],
  ['未知字段', (r: any) => { r.temperature = 0; }],
  ['输出超过上限', (r: any) => { r.max_tokens = 1601; }],
  ['输出不是整数', (r: any) => { r.max_tokens = 1.5; }],
  ['角色错位', (r: any) => { r.messages.reverse(); }],
  ['未知消息字段', (r: any) => { r.messages[1].extra = true; }],
  ['孤立代理', (r: any) => { r.messages[1].content = '\ud800'; }],
  ['特殊图像标记', (r: any) => { r.messages[1].content = '<｜image｜>'; }],
  ['多模态', (r: any) => { r.messages[1].content = []; }],
] as const) {
  test(`计数拒绝${name}且错误不含请求正文`, () => {
    const request = plain();
    change(request);
    assert.throws(() => parseCountingRequest(JSON.stringify(request)), { message: '输入计数不可用' });
  });
}

test('外层及工具 arguments 内层重复 JSON 键都拒绝', () => {
  const raw = JSON.stringify(plain());
  assert.throws(() => parseCountingRequest(raw.replace('"stream":false', '"stream":false,"stream":false')));
  const request = toolHistory();
  const assistant = request.messages.findLast((m: any) => m.role === 'assistant');
  assistant.tool_calls[0].function.arguments = '{"runId":"a","runId":"b"}';
  assert.throws(() => parseCountingRequest(JSON.stringify(request)));
});

test('非有限数值与溢出 JSON 数值都拒绝', () => {
  const raw = JSON.stringify(plain());
  for (const number of ['NaN', 'Infinity', '1e400', '-1e400']) {
    assert.throws(() => parseCountingRequest(raw.replace(/"max_tokens":\d+/, `"max_tokens":${number}`)),
      { message: '输入计数不可用' });
  }
});

test('只接受当前 schema 结构，但保留合法字段顺序与描述文字', () => {
  const request = structuredClone(golden[1].request);
  const fn = request.tools[0].function;
  fn.description = '新描述 "冒号:逗号,"\u000b🏃';
  fn.parameters = { additionalProperties: false, required: [], properties: {}, type: 'object' };
  assert.deepEqual(parseCountingRequest(JSON.stringify(request)), request);
  fn.parameters.properties = { extra: { type: 'number' } };
  assert.throws(() => parseCountingRequest(JSON.stringify(request)));
});

test('工具声明重复、缺失结果、重复结果及未知调用均拒绝', () => {
  const missing = toolHistory();
  missing.messages.pop();
  assert.throws(() => parseCountingRequest(JSON.stringify(missing)));
  const duplicate = toolHistory();
  duplicate.messages.push(duplicate.messages.at(-1));
  assert.throws(() => parseCountingRequest(JSON.stringify(duplicate)));
  const unknown = toolHistory();
  unknown.messages.find((m: any) => m.role === 'assistant').tool_calls[0].function.name = 'other';
  assert.throws(() => parseCountingRequest(JSON.stringify(unknown)));
  const tools = structuredClone(golden[1].request);
  tools.tools[1] = tools.tools[0];
  assert.throws(() => parseCountingRequest(JSON.stringify(tools)));
});

test('按 UTF-8 字节校验 64 KiB 边界，不按字符数', () => {
  const request = plain();
  request.messages[1].content = '';
  const overhead = Buffer.byteLength(JSON.stringify(request));
  request.messages[1].content = 'a'.repeat(65536 - overhead);
  assert.equal(Buffer.byteLength(JSON.stringify(request)), 65536);
  assert.doesNotThrow(() => parseCountingRequest(JSON.stringify(request)));
  request.messages[1].content += 'a';
  assert.throws(() => parseCountingRequest(JSON.stringify(request)));
  request.messages[1].content = '流'.repeat(22000);
  assert.throws(() => parseCountingRequest(JSON.stringify(request)));
});
