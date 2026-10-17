import { expect, it } from 'vitest';
import { runAgent } from '../src/loop.ts';
import { parseSubmission } from '../src/cloud-task-contracts.ts';

it('真实运行时保留授权记忆并作为用户资料生成结果，下一任务不继承', async () => {
  const memory = { id: '11111111-1111-4111-8111-111111111111', version: 1, content: '喜欢晚跑😊' };
  const submission = { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(), prompt: '今天怎么安排', consent: { cloudProcessing: true, runSummaries: false }, summaries: [], memoryContext: { version: 'selected_memories_v1', approved: true, items: [memory] } };
  const requests = [];
  const ports = {
    model: { async generate(request) { requests.push(request); return { decision: { kind: 'final', text: '可以按你喜欢的晚跑时间来安排。' }, usage: { inputTokens: 200, outputTokens: 30 } }; } },
    budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
  };
  expect((await runAgent(parseSubmission(submission).task, ports)).status).toBe('completed');
  expect(requests[0].messages).toEqual([{ role: 'user', text: JSON.stringify({ selected_memories_v1: [memory] }) }, { role: 'user', text: submission.prompt }]);
  const { memoryContext, ...plain } = submission;
  expect(memoryContext.approved).toBe(true);
  expect((await runAgent(parseSubmission(plain).task, ports)).status).toBe('completed');
  expect(requests[1].messages).toEqual([{ role: 'user', text: submission.prompt }]);
});
