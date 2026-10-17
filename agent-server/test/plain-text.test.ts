import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseSubmission } from '../src/cloud-task-contracts.ts';

const submission = (prompt: string) => ({
  clientRequestId: '01234567-1234-4123-8123-012345678901',
  localTaskId: '01234567-1234-4123-8123-012345678902',
  prompt,
  consent: { cloudProcessing: true, runSummaries: false },
  summaries: [],
});

for (const [name, prompt] of [
  ['孤立高代理', '\ud800'],
  ['孤立低代理', '\udfff'],
  ['高代理后接普通字符', '\ud800a'],
  ['合法配对后接孤立代理', '🏃\ud800'],
  ['保留图像标记', '文字<｜image｜>'],
  ['保留 DeepSeek 图像标记', '<｜deepseek_image｜>文字'],
] as const) {
  test(`接单前拒绝${name}，不能延后变成模型故障`, () => {
    assert.throws(() => parseSubmission(submission(prompt)), { code: 'invalid_request' });
  });
}

test('合法 Unicode、控制符和 ASCII image 标签仍原样保留，仅沿用入口 trim', () => {
  const text = '𠀀👩🏽‍💻e\u0301\u0000\r\n<image>文字</image><｜User｜>';
  assert.equal(parseSubmission(submission(`  ${text}  `)).task.prompt, text);
});
