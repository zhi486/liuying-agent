// 一次性合成大输入：走真实 Loop/协议转换，计数预检即停止，供应商与账本均为测试端口。
import assert from 'node:assert/strict';
import { runAgent } from '../../src/loop.ts';
import { createDeepseekModel } from '../../src/deepseek.ts';

const summaries = Array.from({ length: 20 }, (_, index) => ({
  runId: `run_${String(index).padStart(2, '0')}_${'x'.repeat(73)}`,
  distanceMeters: 12345.67890123456,
  durationSeconds: 23456.78901234567,
  averagePaceSecPerKm: 345.6789012345678,
  averageHeartRate: 156.7890123456789,
}));
const cases = [];
let sent = 0;
const result = await runAgent(
  {
    taskId: 'spike_fixture',
    prompt: '跑'.repeat(2000),
    consent: { cloudProcessing: true, runSummaries: true },
    summaries,
  },
  {
    budget: {
      reserve: async ({ requestId }) => ({ reservationId: requestId }),
      settle: async () => {},
    },
    model: {
      async generate(request, signal) {
        let captured;
        const wire = createDeepseekModel({
          apiKey: 'fixture-only-not-a-secret',
          countInputTokens: async (body) => {
            captured = JSON.parse(body);
            throw new Error('虚构请求导出到此停止');
          },
          fetch: async () => {
            sent++;
            throw new Error('禁止联网');
          },
        });
        await assert.rejects(wire.generate(request, signal));
        assert.ok(captured);
        const step = cases.length + 1;
        cases.push({ name: `loop-large-round-${step}`, request: captured });
        const decision =
          step === 3
            ? { kind: 'final', text: '虚构探针完成。' }
            : {
                kind: 'tools',
                calls:
                  step === 1
                    ? [{ id: 'list_1', name: 'runs.list', args: {} }]
                    : [
                        {
                          id: 'summary_1',
                          name: 'runs.summary',
                          args: { runId: summaries[0].runId },
                        },
                      ],
              };
        // 固定 usage 仅推动测试端口，不是此次请求的真实计数或账单。
        return { decision, usage: { inputTokens: 100, outputTokens: 40 } };
      },
    },
  },
);
assert.equal(result.status, 'completed');
assert.equal(sent, 0);
assert.equal(cases.length, 3);
console.log(JSON.stringify(cases));
