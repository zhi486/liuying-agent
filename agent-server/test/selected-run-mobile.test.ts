import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { parseSubmission } from '../src/cloud-task-contracts.ts';
import { runAgent } from '../src/loop.ts';
import type { ModelPort } from '../src/contracts.ts';

const submission = JSON.parse(readFileSync(new URL('../../contracts/agent/selected-run-request.json', import.meta.url), 'utf8'));

test('手机单条摘要契约经过正式解析器和三轮只读工具循环，不自动读取其他历史', async () => {
  const { task } = parseSubmission(submission);
  const requests: Parameters<ModelPort['generate']>[0][] = [];
  const decisions = [
    { kind: 'tools', calls: [{ id: 'list', name: 'runs.list', args: {} }] },
    { kind: 'tools', calls: [{ id: 'summary', name: 'runs.summary', args: { runId: 'run-1' } }] },
    { kind: 'final', text: '本次两公里用时十二分钟，没有心率数据，不能判断心率变化。' },
  ];
  const result = await runAgent(task, {
    model: { async generate(request) {
      requests.push(structuredClone(request));
      return { decision: decisions[requests.length - 1], usage: { inputTokens: 100, outputTokens: 40 } };
    } },
    budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
  });
  assert.equal(result.status, 'completed');
  assert.equal(requests.length, 3);
  assert.deepEqual(requests[1]!.messages[2], { role: 'tool', name: 'runs.list', callId: 'list', data: { runs: submission.summaries } });
  assert.deepEqual(requests[2]!.messages[4], { role: 'tool', name: 'runs.summary', callId: 'summary', data: { run: submission.summaries[0] } });
  assert.equal(JSON.stringify(requests).includes('latitude'), false);
  assert.equal(submission.summaries[0].averageHeartRate, null);
});

test('同一手机样本取消摘要授权或夹带原始轨迹时拒绝', () => {
  assert.throws(() => parseSubmission({ ...submission, consent: { cloudProcessing: true, runSummaries: false } }), /invalid_request/);
  assert.throws(() => parseSubmission({ ...submission, summaries: [{ ...submission.summaries[0], route: [] }] }), /invalid_request/);
});
