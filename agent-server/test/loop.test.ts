import assert from 'node:assert/strict';
import { setImmediate as flush } from 'node:timers/promises';
import { test } from 'node:test';
import type { BudgetPort, ModelPort, Settlement } from '../src/contracts.ts';
import { runAgent } from '../src/loop.ts';
import { AgentError } from '../src/contracts.ts';

test('费用结算后重新检查执行权，撤权后不执行只读工具或下一步模型', async () => {
  const h = harness([tools(summaryCall('call_1', 'run_1')), final]);
  const result = await runAgent(task, h, {
    ...{
      checkExecution: async () => {
        if (h.settlements.length > 0) throw new AgentError('cancelled');
      },
    },
  });
  assert.equal(result.status, 'cancelled');
  assert.deepEqual(result.actions, [{ kind: 'model_requested', step: 1 }]);
  assert.equal(h.requests.length, 1);
  assert.equal(h.settlements.length, 1);
});

const task = {
  taskId: 'task_1',
  prompt: '根据我的跑步摘要复盘',
  consent: { cloudProcessing: true, runSummaries: true },
  summaries: Array.from({ length: 8 }, (_, index) => ({
    runId: `run_${index + 1}`,
    distanceMeters: 4000,
    durationSeconds: 1800,
    averagePaceSecPerKm: 450,
    averageHeartRate: 145,
  })),
};
const usage = { inputTokens: 100, outputTokens: 40 };
const final = { kind: 'final', text: '本次四公里，用时三十分钟。' };
const summaryCall = (id: string, runId: string) => ({
  id,
  name: 'runs.summary',
  args: { runId },
});
const tools = (...calls: unknown[]) => ({ kind: 'tools', calls });
const reply = (decision: unknown) => ({ decision, usage });

function harness(decisions: unknown[] = [final]) {
  const requests: Parameters<ModelPort['generate']>[0][] = [];
  const order: string[] = [];
  const settlements: { id: string; value: Settlement }[] = [];
  const model: ModelPort = {
    async generate(request) {
      order.push('model');
      requests.push(structuredClone(request));
      return reply(decisions[requests.length - 1]);
    },
  };
  const budget: BudgetPort = {
    async reserve(request) {
      order.push('reserve');
      return { reservationId: request.requestId };
    },
    async settle(id, value) {
      order.push('settle');
      settlements.push({ id, value });
    },
  };
  return { model, budget, requests, order, settlements };
}

test('真正执行模型选择、只读工具、观察结果、最终答复的循环', async () => {
  const h = harness([tools(summaryCall('call_1', 'run_1')), final]);
  const result = await runAgent(task, h);
  assert.equal(result.status, 'completed');
  if (result.status === 'completed') assert.equal(result.answer, final.text);
  assert.equal(h.requests.length, 2);
  assert.deepEqual(h.requests[0]?.messages, [{ role: 'user', text: task.prompt }]);
  assert.deepEqual(h.requests[1]?.messages[2], {
    role: 'tool',
    name: 'runs.summary',
    callId: 'call_1',
    data: { run: task.summaries[0] },
  });
  assert.deepEqual(result.actions, [
    { kind: 'model_requested', step: 1 },
    { kind: 'tool_completed', step: 1, tool: 'runs.summary' },
    { kind: 'model_requested', step: 2 },
  ]);
  assert.deepEqual(h.order, ['reserve', 'model', 'settle', 'reserve', 'model', 'settle']);
  assert.deepEqual(
    h.settlements.map((s) => s.value),
    [
      { kind: 'usage', usage },
      { kind: 'usage', usage },
    ],
  );
  assert.equal(h.requests[0]?.requestId, 'task_1:1');
  assert.equal(h.requests[1]?.requestId, 'task_1:2');
  assert.deepEqual(h.requests[0]?.limits, {
    maxInputTokens: 20000,
    maxOutputTokens: 1600,
  });
});

test('没有工具调用也能完成一般任务，不自动发送所有摘要', async () => {
  const h = harness();
  assert.equal((await runAgent(task, h)).status, 'completed');
  assert.equal(JSON.stringify(h.requests).includes('averageHeartRate'), false);
});

test('最多三次模型请求；最后一轮不再执行没有机会观察的工具', async () => {
  const h = harness([
    tools(summaryCall('a', 'run_1')),
    tools(summaryCall('b', 'run_2')),
    tools(summaryCall('c', 'run_3')),
  ]);
  const result = await runAgent(task, h);
  assert.equal(result.status, 'blocked');
  assert.equal('reason' in result && result.reason, 'step_limit');
  assert.equal(h.requests.length, 3);
  assert.equal(result.actions.filter((a) => a.kind === 'tool_completed').length, 2);
});

test('工具总量限制六次，第七次不执行', async () => {
  const h = harness([
    tools(...task.summaries.slice(0, 6).map((r, i) => summaryCall(`a${i}`, r.runId))),
    tools(summaryCall('seventh', 'run_7')),
  ]);
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'tool_limit');
  assert.equal(result.actions.filter((a) => a.kind === 'tool_completed').length, 6);
});

for (const decisions of [
  [tools(summaryCall('a', 'run_1')), tools(summaryCall('b', 'run_1'))],
  [tools(summaryCall('a', 'run_1')), tools(summaryCall('a', 'run_2'))],
  [tools(summaryCall('a', 'run_1'), summaryCall('b', 'run_1'))],
]) {
  test('拒绝重复调用内容或调用 ID，不额外执行工具', async () => {
    const h = harness(decisions);
    const result = await runAgent(task, h);
    assert.equal('reason' in result && result.reason, 'duplicate_tool');
    assert.ok(result.actions.filter((a) => a.kind === 'tool_completed').length <= 1);
  });
}

test('整批只读工具通过后才发布观察；后一个越权时不发布前一个结果', async () => {
  const h = harness([tools(summaryCall('a', 'run_1'), summaryCall('b', 'other_user'))]);
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'scope_denied');
  assert.equal(result.actions.filter((a) => a.kind === 'tool_completed').length, 0);
  assert.equal(h.requests.length, 1);
});

for (const decision of [
  null,
  { kind: 'final', text: '' },
  { kind: 'final', text: '字'.repeat(4001) },
  { ...final, reasoning: '内部思考不进入结果' },
  { kind: 'tools', calls: [] },
  tools({ id: 'a', name: 'settings.update', args: {} }),
]) {
  test(`拒绝非法模型输出：${JSON.stringify(decision)?.slice(0, 80)}`, async () => {
    const h = harness([decision]);
    const result = await runAgent(task, h);
    assert.ok(
      'reason' in result && ['invalid_model_output', 'invalid_tool'].includes(result.reason),
    );
    assert.equal(h.requests.length, 1);
    assert.equal('answer' in result, false);
    assert.equal(h.settlements[0]?.value.kind, 'usage');
  });
}

test('预算拒绝在请求之前生效', async () => {
  const h = harness();
  h.budget.reserve = async () => null;
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'budget_denied');
  assert.equal(h.requests.length, 0);
  assert.equal(h.settlements.length, 0);
});

test('额度服务失败不降级为免费调用，错误信息不回传', async () => {
  const h = harness();
  h.budget.reserve = async () => {
    throw new Error('供应商私密诊断');
  };
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'budget_unavailable');
  assert.equal(h.requests.length, 0);
  assert.equal(JSON.stringify(result).includes('私密'), false);
});

test('已发送模型调用失败按不确定费用保留预留，不重试', async () => {
  const h = harness();
  h.model.generate = async () => {
    throw new Error('敏感供应商响应');
  };
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'model_unavailable');
  assert.equal(h.settlements[0]?.value.kind, 'uncertain');
  assert.equal(JSON.stringify(result).includes('敏感'), false);
});

for (const invalidUsage of [
  null,
  { inputTokens: -1, outputTokens: 1 },
  { inputTokens: 1.5, outputTokens: 1 },
  { inputTokens: 1, outputTokens: 1, secret: '不能接受' },
]) {
  test('无效或缺失用量不当作零费用', async () => {
    const h = harness();
    h.model.generate = async () => ({ decision: final, usage: invalidUsage });
    const result = await runAgent(task, h);
    assert.equal('reason' in result && result.reason, 'usage_invalid');
    assert.equal(h.settlements[0]?.value.kind, 'uncertain');
  });
}

test('结算失败停止，不启动下一次模型请求', async () => {
  const h = harness([tools(summaryCall('a', 'run_1')), final]);
  h.budget.settle = async () => {
    throw new Error('结算不可用');
  };
  const result = await runAgent(task, h);
  assert.equal('reason' in result && result.reason, 'budget_unavailable');
  assert.equal(h.requests.length, 1);
});

for (const excessiveUsage of [
  { inputTokens: 20001, outputTokens: 40 },
  { inputTokens: 100, outputTokens: 1601 },
]) {
  test('供应商突破 token 预留边界时保留真实用量并熔断后续调用', async () => {
    const h = harness();
    let calls = 0;
    h.model.generate = async () => {
      calls++;
      return {
        decision: tools(summaryCall(`a${calls}`, `run_${calls}`)),
        usage: excessiveUsage,
      };
    };
    const result = await runAgent(task, h);
    assert.equal('reason' in result && result.reason, 'usage_limit');
    assert.equal(calls, 1);
    assert.deepEqual(h.settlements[0]?.value, {
      kind: 'usage',
      usage: excessiveUsage,
    });
  });
}

test('完整最终回答允许超过旧 600 token 上限直至 1600，并按实际用量结算', async () => {
  for (const outputTokens of [601, 1600]) {
    const h = harness();
    const actualUsage = { inputTokens: 100, outputTokens };
    h.model.generate = async () => ({ decision: final, usage: actualUsage });
    const result = await runAgent(task, h);
    assert.equal(result.status, 'completed');
    assert.deepEqual(h.settlements[0]?.value, { kind: 'usage', usage: actualUsage });
  }
});

test('取消后模型晚到拒绝被消费，不触发重试或改写结算', async () => {
  const h = harness();
  let fail!: (reason: unknown) => void;
  h.model.generate = async () =>
    new Promise((_resolve, reject) => {
      fail = reject;
    });
  const result = await runAgent(task, h, { timeoutMs: 25 });
  fail(new Error('迟到的私密诊断'));
  await flush();
  assert.equal(result.status, 'timed_out');
  assert.equal(h.settlements.length, 1);
  assert.equal(h.settlements[0]?.value.kind, 'uncertain');
});

test('缺失授权在预算与模型之前拒绝', async () => {
  const h = harness();
  const result = await runAgent(
    { ...task, consent: { cloudProcessing: false, runSummaries: true } },
    h,
  );
  assert.equal('reason' in result && result.reason, 'consent_required');
  assert.deepEqual(h.order, []);
});

test('进入时已取消，不创建预留和模型请求', async () => {
  const h = harness();
  const result = await runAgent(task, h, { signal: AbortSignal.abort() });
  assert.equal(result.status, 'cancelled');
  assert.deepEqual(h.order, []);
});

test('调用中取消向模型传播，未知费用保留，永不返回的模型也不阻塞任务', async () => {
  const h = harness();
  const controller = new AbortController();
  let modelSignal: AbortSignal | undefined;
  h.model.generate = async (_request, signal) => {
    modelSignal = signal;
    controller.abort();
    return new Promise(() => {});
  };
  const result = await runAgent(task, h, { signal: controller.signal });
  assert.equal(result.status, 'cancelled');
  assert.equal(modelSignal?.aborted, true);
  assert.equal(h.settlements[0]?.value.kind, 'uncertain');
});

test('整体截止时间终止不响应取消的模型，保留费用', async () => {
  const h = harness();
  h.model.generate = async () => new Promise(() => {});
  const result = await runAgent(task, h, { timeoutMs: 25 });
  assert.equal(result.status, 'timed_out');
  assert.equal(h.settlements[0]?.value.kind, 'uncertain');
});

test('预留服务等待也算入截止时间；晚到预留释放，不能晚发模型请求', async () => {
  const h = harness();
  const background: Promise<void>[] = [];
  let drained = false;
  let grant!: (value: { reservationId: string }) => void;
  h.budget.reserve = async () =>
    new Promise((resolve) => {
      grant = resolve;
    });
  const result = await runAgent(task, h, {
    timeoutMs: 25,
    ...{
      waitUntil: (pending: Promise<void>) => {
        background.push(pending);
        void pending.then(() => {
          drained = true;
        });
      },
    },
  });
  assert.equal(result.status, 'timed_out');
  assert.equal(background.length, 1);
  assert.equal(drained, false);
  grant({ reservationId: 'late_reservation' });
  await Promise.all(background);
  assert.equal(drained, true);
  assert.equal(h.requests.length, 0);
  assert.deepEqual(h.settlements, [{ id: 'late_reservation', value: { kind: 'not_dispatched' } }]);
});

test('预留完成同时取消时释放，不发送模型', async () => {
  const h = harness();
  const controller = new AbortController();
  h.budget.reserve = async () => {
    controller.abort();
    return { reservationId: 'not_sent' };
  };
  assert.equal((await runAgent(task, h, { signal: controller.signal })).status, 'cancelled');
  await flush();
  assert.equal(h.requests.length, 0);
  assert.deepEqual(h.settlements, [{ id: 'not_sent', value: { kind: 'not_dispatched' } }]);
});

test('结算服务不返回也受整体截止时间约束', async () => {
  const h = harness();
  let release!: () => void;
  let settled = false;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const background: Promise<void>[] = [];
  h.budget.settle = async () => {
    await gate;
    settled = true;
  };
  assert.equal(
    (
      await runAgent(task, h, {
        timeoutMs: 25,
        ...{
          waitUntil: (pending: Promise<void>) => {
            background.push(pending);
          },
        },
      })
    ).status,
    'timed_out',
  );
  assert.equal(background.length, 1);
  assert.equal(settled, false);
  assert.equal(h.requests.length, 1);
  release();
  await Promise.all(background);
  assert.equal(settled, true);
});

test('计费上限由可信核心固定，调用方不能放宽超时', async () => {
  const h = harness();
  assert.equal((await runAgent(task, h, { timeoutMs: 30001 })).status, 'failed');
  assert.equal(h.requests.length, 0);
});
