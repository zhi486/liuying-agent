import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { renderInputPrompt } from '../src/token-count/template.ts';
import { referenceCases, referenceRequests, sha256 } from './token-reference.ts';

const golden = JSON.parse(readFileSync(new URL('../tools/token-count/golden.json', import.meta.url), 'utf8'));

test('普通文本模板不重复特殊标记、不遗漏生成 header', () => {
  assert.equal(renderInputPrompt(JSON.stringify(golden[0].request)),
    '<｜begin▁of▁sentence｜><｜System｜>只读助手<｜User｜>你好<｜Assistant｜></think>');
});

test('连续用户消息按官方双换行合并，不额外插入角色标记', () => {
  const request = structuredClone(golden[0].request);
  request.messages.push({ role: 'user', content: '第二句\n🏃' });
  assert.equal(renderInputPrompt(JSON.stringify(request)),
    '<｜begin▁of▁sentence｜><｜System｜>只读助手<｜User｜>你好\n\n第二句\n🏃<｜Assistant｜></think>');
});

test('7 个冻结 golden 的模板摘要保持不变', () => {
  for (const item of golden) assert.equal(sha256(renderInputPrompt(JSON.stringify(item.request))), item.expected.promptSha256, item.name);
});

test('146 组请求由 JS 独立渲染后与官方 Python 完整字节相同', () => {
  const cases = referenceCases();
  assert.equal(cases.length, 146);
  for (const item of cases) assert.equal(
    sha256(renderInputPrompt(JSON.stringify(item.request))), sha256(item.prompt), item.name);
});

test('工具顺序、参数、schema 转义和混合 user/tool 的新请求与参考相同', () => {
  const base = structuredClone(golden[1].request);
  base.tools.reverse();
  base.tools[0].function.description = '引号"斜线\\冒号:逗号,\b\t\n\f\r\u000b\u0000\u007f\u2028🏃';
  base.tools[0].function.parameters = {
    additionalProperties: false, required: ['runId'], properties: { runId: { type: 'string' } }, type: 'object',
  };
  base.messages.push({ role: 'assistant', content: null, tool_calls: [
    { id: 'a', type: 'function', function: { name: 'runs_list', arguments: '{}' } },
    { id: 'b', type: 'function', function: { name: 'runs_summary', arguments: JSON.stringify({ runId: '跑步"\u000b🏃' }) } },
  ] }, { role: 'tool', tool_call_id: 'b', content: '{"n":1e+21,"text":"<image>保留字面量</image>"}' },
  { role: 'tool', tool_call_id: 'a', content: '工具一\r\n结果' }, { role: 'user', content: '继续\u0000' });
  const raw = JSON.stringify(base);
  assert.equal(renderInputPrompt(raw), referenceRequests([raw])[0]!.prompt);
  assert.ok(renderInputPrompt(raw).includes('<｜DSML｜ invoke name="runs_list">\n\n</｜DSML｜ invoke>'));
  assert.ok(renderInputPrompt(raw).indexOf('<tool_result>工具一') < renderInputPrompt(raw).indexOf('<tool_result>{"n"'));
});
