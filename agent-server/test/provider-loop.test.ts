import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDeepseekModel } from '../src/deepseek.ts';
import { createD1Budget } from '../src/budget.ts';
import { runAgent } from '../src/loop.ts';
import { openBudgetDatabase } from './sqlite-d1.ts';

const timestamp = Date.UTC(2026, 8, 14, 10);
const task = {
  taskId: 'task_1',
  prompt: '复盘这次跑步',
  consent: { cloudProcessing: true, runSummaries: true },
  summaries: [
    {
      runId: 'run_1',
      distanceMeters: 4000,
      durationSeconds: 1800,
      averagePaceSecPerKm: 450,
      averageHeartRate: 145,
    },
  ],
};
const fixture = () => {
  const database = openBudgetDatabase();
  database.sqlite
    .prepare('INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, ?)')
    .run('owner_1', 13440);
  const budget = createD1Budget({
    db: database.db,
    ownerId: 'owner_1',
    now: () => timestamp,
    rate: {
      model: 'deepseek-flash',
      version: 'fixture_peak',
      inputMicrosPerMillion: 300000,
      outputMicrosPerMillion: 1200000,
      checkedAtMillis: timestamp,
      expiresAtMillis: timestamp + 86400000,
    },
  });
  return { ...database, budget };
};
const usage = { prompt_tokens: 100, completion_tokens: 40, total_tokens: 140 };

test('真实适配器、持久账本与 Agent Loop 完成工具选择→摘要观察→最终答复', async (t) => {
  const database = fixture();
  t.after(database.close);
  const sent: Record<string, unknown>[] = [];
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async (_, init) => {
      sent.push(JSON.parse(init!.body as string));
      if (sent.length === 1)
        return Response.json({
          usage,
          choices: [
            {
              index: 0,
              finish_reason: 'tool_calls',
              message: {
                role: 'assistant',
                content: null,
                tool_calls: [
                  {
                    id: 'call_1',
                    type: 'function',
                    function: { name: 'runs_summary', arguments: '{"runId":"run_1"}' },
                  },
                ],
              },
            },
          ],
        });
      return Response.json({
        usage,
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: { role: 'assistant', content: '根据 run_1，你跑了四公里，用时三十分钟。' },
          },
        ],
      });
    },
  });
  const result = await runAgent(task, { model, budget: database.budget });
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail('必须完成两轮循环');
  assert.equal(result.answer, '根据 run_1，你跑了四公里，用时三十分钟。');
  assert.deepEqual(result.actions, [
    { kind: 'model_requested', step: 1 },
    { kind: 'tool_completed', step: 1, tool: 'runs.summary' },
    { kind: 'model_requested', step: 2 },
  ]);
  assert.equal(sent.length, 2);
  assert.equal(JSON.stringify(sent[0]).includes('4000'), false);
  const messages = sent[1]!.messages as Record<string, unknown>[];
  assert.deepEqual(messages[3], {
    role: 'tool',
    tool_call_id: 'call_1',
    content: JSON.stringify({ run: task.summaries[0] }),
  });
  const rows = database.sqlite
    .prepare(
      'SELECT request_id, state, charged_micros FROM agent_budget_reservations ORDER BY request_id',
    )
    .all();
  assert.deepEqual(
    rows.map((row) => ({ ...row })),
    [
      { request_id: 'task_1:1', state: 'settled', charged_micros: 78 },
      { request_id: 'task_1:2', state: 'settled', charged_micros: 78 },
    ],
  );
  const repeated = await runAgent(task, { model, budget: database.budget });
  assert.equal(repeated.status, 'blocked');
  assert.equal(sent.length, 2);
});

test('传输失败、用量缺失和发送前计数失败均保留预留，不重试模型', async (t) => {
  for (const mode of ['network', 'usage', 'counter']) {
    await t.test(mode, async (t) => {
      const database = fixture();
      t.after(database.close);
      let calls = 0;
      const model = createDeepseekModel({
        apiKey: 'test-only-key',
        countInputTokens: async () => {
          if (mode === 'counter') throw new Error('测试计数失败');
          return 100;
        },
        fetch: async () => {
          calls++;
          if (mode === 'network') throw new Error('测试断网');
          return Response.json({
            choices: [
              {
                index: 0,
                finish_reason: 'stop',
                message: { role: 'assistant', content: '测试答复' },
              },
            ],
          });
        },
      });
      const result = await runAgent(task, { model, budget: database.budget });
      assert.equal(result.status, 'failed');
      assert.equal(calls, mode === 'counter' ? 0 : 1);
      const row = database.sqlite.prepare('SELECT * FROM agent_budget_reservations').get()!;
      assert.equal(row.state, 'uncertain');
      assert.equal(row.reserved_micros, 6720);
      assert.equal(row.charged_micros, null);
    });
  }
});

test('供应商输出非法但用量有效时仍真实记账，越权工具不执行', async (t) => {
  const database = fixture();
  t.after(database.close);
  const model = createDeepseekModel({
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () =>
      Response.json({
        usage,
        choices: [
          {
            index: 0,
            finish_reason: 'tool_calls',
            message: {
              role: 'assistant',
              content: null,
              tool_calls: [
                {
                  id: 'call_1',
                  type: 'function',
                  function: { name: 'delete_all', arguments: '{}' },
                },
              ],
            },
          },
        ],
      }),
  });
  const result = await runAgent(task, { model, budget: database.budget });
  assert.equal(result.status, 'failed');
  assert.deepEqual(result.actions, [{ kind: 'model_requested', step: 1 }]);
  assert.equal(
    database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
});
