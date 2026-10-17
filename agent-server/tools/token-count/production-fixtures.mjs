// 使用真实适配器导出虚构请求；在计数预检处停止，绝不发送 HTTP。
import assert from 'node:assert/strict';
import { createDeepseekModel } from '../../src/deepseek.ts';
import { TOOL_DEFINITIONS } from '../../src/tools.ts';
import { MODEL_TOKEN_LIMITS } from '../../src/model-limits.ts';

const messages = [{ role: 'user', text: '请复盘最近一次跑步🏃，只使用已授权摘要。' }];
const cases = [];
for (let step = 1; step <= 3; step++) {
  let captured;
  let sent = 0;
  const model = createDeepseekModel({
    apiKey: 'fixture-only-not-a-secret',
    countInputTokens: async (body) => {
      captured = JSON.parse(body);
      throw new Error('离线导出到此停止');
    },
    fetch: async () => {
      sent++;
      throw new Error('禁止联网');
    },
  });
  await assert.rejects(
    model.generate(
      {
        requestId: `fixture:${step}`,
        instructions: '只读助手',
        messages: structuredClone(messages),
        tools: TOOL_DEFINITIONS,
        limits: MODEL_TOKEN_LIMITS,
      },
      new AbortController().signal,
    ),
  );
  assert.equal(sent, 0);
  assert.ok(captured);
  cases.push({ name: `production-round-${step}`, request: captured });
  if (step === 1) {
    messages.push(
      {
        role: 'assistant',
        calls: [{ id: 'call_list', name: 'runs.list', args: {} }],
      },
      {
        role: 'tool',
        callId: 'call_list',
        name: 'runs.list',
        data: {
          runs: [
            {
              runId: 'r_1',
              distanceMeters: 4000,
              durationSeconds: 1800,
              averagePaceSecPerKm: 450,
              averageHeartRate: 150,
            },
          ],
        },
      },
    );
  } else if (step === 2) {
    messages.push(
      {
        role: 'assistant',
        calls: [{ id: 'call_summary', name: 'runs.summary', args: { runId: 'r_1' } }],
      },
      {
        role: 'tool',
        callId: 'call_summary',
        name: 'runs.summary',
        data: {
          run: {
            runId: 'r_1',
            distanceMeters: 4000,
            durationSeconds: 1800,
            averagePaceSecPerKm: 450,
            averageHeartRate: null,
          },
        },
      },
    );
  }
}
console.log(JSON.stringify(cases));
