import assert from 'node:assert/strict';
import { test } from 'node:test';
import { executeTool, parseTask, validateToolCall } from '../src/tools.ts';

const summary = {
  runId: 'run_1',
  distanceMeters: 4000,
  durationSeconds: 1800,
  averagePaceSecPerKm: 450,
  averageHeartRate: 145,
};

function input(overrides: Record<string, unknown> = {}) {
  return {
    taskId: 'task_1',
    prompt: '复盘最近一次跑步',
    consent: { cloudProcessing: true, runSummaries: true },
    summaries: [summary],
    ...overrides,
  };
}

test('白名单工具仅读取授权快照，参数经过验证', () => {
  const task = parseTask(input());
  assert.deepEqual(executeTool(validateToolCall({ id: 'a', name: 'runs.list', args: {} }), task), {
    runs: [summary],
  });
  assert.deepEqual(
    executeTool(
      validateToolCall({ id: 'b', name: 'runs.summary', args: { runId: 'run_1' } }),
      task,
    ),
    { run: summary },
  );
});

test('没有跑步摘要权限仍可接受一般任务，但读取工具被拒绝', () => {
  const task = parseTask(
    input({ summaries: [], consent: { cloudProcessing: true, runSummaries: false } }),
  );
  assert.throws(
    () => executeTool(validateToolCall({ id: 'a', name: 'runs.list', args: {} }), task),
    /scope_denied/,
  );
});

test('摘要授权可撤回，不能携带未获准的记录', () => {
  assert.throws(
    () => parseTask(input({ consent: { cloudProcessing: true, runSummaries: false } })),
    /consent_required/,
  );
  assert.throws(
    () => parseTask(input({ consent: { cloudProcessing: false, runSummaries: true } })),
    /consent_required/,
  );
});

test('越权 ID 不读取，错误不泄露请求或运动数据', () => {
  const task = parseTask(input());
  assert.throws(
    () =>
      executeTool(
        validateToolCall({ id: 'a', name: 'runs.summary', args: { runId: 'private_run' } }),
        task,
      ),
    /^Error: scope_denied$/,
  );
});

for (const call of [
  { id: 'a', name: 'http.fetch', args: {} },
  { id: 'a', name: 'runs.list', args: { limit: 99 } },
  { id: 'a', name: 'runs.summary', args: { runId: 'run_1', sql: 'SELECT *' } },
  { id: 'a', name: 'runs.summary', args: {} },
  { id: 'a', name: 'runs.list', args: {}, reasoning: '不应执行' },
  { id: '', name: 'runs.list', args: {} },
  { id: 'a', name: 'runs.list', args: [] },
]) {
  test(`拒绝非法调用：${JSON.stringify(call)}`, () => {
    assert.throws(() => validateToolCall(call));
  });
}

for (const invalid of [
  input({ prompt: '' }),
  input({ prompt: '字'.repeat(2001) }),
  input({ taskId: '../task' }),
  input({ rawGps: [1, 2] }),
  input({ summaries: [{ ...summary, latitude: 1 }] }),
  input({ summaries: [{ ...summary, averageHeartRate: Number.NaN }] }),
  input({ summaries: [{ ...summary, distanceMeters: -1 }] }),
  input({ summaries: [{ ...summary, durationSeconds: 0 }] }),
  input({ summaries: [summary, summary] }),
  input({ summaries: Array.from({ length: 21 }, (_, i) => ({ ...summary, runId: `run_${i}` })) }),
]) {
  test(`拒绝非法任务或非最小摘要：${JSON.stringify(invalid).slice(0, 100)}`, () => {
    assert.throws(() => parseTask(invalid));
  });
}

test('缺失指标以 null 保留，不伪造心率或配速', () => {
  const task = parseTask(
    input({ summaries: [{ ...summary, averagePaceSecPerKm: null, averageHeartRate: null }] }),
  );
  assert.equal(task.summaries[0]?.averageHeartRate, null);
});

test('验证产生独立快照，调用方事后修改不能扩大数据范围', () => {
  const source = input({ summaries: [{ ...summary }] });
  const task = parseTask(source);
  (source.summaries as (typeof summary)[])[0]!.runId = 'changed';
  assert.equal(task.summaries[0]?.runId, 'run_1');
});
