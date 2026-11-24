import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDeepseekModel } from '../src/deepseek.ts';
import { createD1Budget } from '../src/budget.ts';
import { runAgent } from '../src/loop.ts';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { parseCountingRequest } from '../src/token-count/request.ts';

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

test('没有授权摘要时真实请求不提供跑步工具，新对话询问记录而已有文字仍可回答', async (t) => {
  for (const mode of ['new', 'empty_selection', 'follow_up']) {
    await t.test(mode, async (t) => {
      const database = fixture();
      t.after(database.close);
      const answer = mode === 'follow_up'
        ? '根据本会话上一条回答，这次用时三十分钟。'
        : '我还没有这次跑步记录，请从“跑步记录”选择对应记录，再点击“让流萤复盘本次跑步”。';
      const sent: ReturnType<typeof parseCountingRequest>[] = [];
      const model = createDeepseekModel({
        apiKey: 'test-only-key',
        countInputTokens: async raw => {
          sent.push(parseCountingRequest(raw));
          return 100;
        },
        fetch: async () => Response.json({
          usage,
          choices: [{ index: 0, finish_reason: sent[0]!.tools.length ? 'tool_calls' : 'stop',
            message: sent[0]!.tools.length ? {
              role: 'assistant', content: null,
              tool_calls: [{ id: 'call_1', type: 'function', function: { name: 'runs_list', arguments: '{}' } }],
            } : { role: 'assistant', content: answer },
          }],
        }),
      });
      const result = await runAgent({ ...task, prompt: '我刚才那次跑步用了多久',
        consent: { cloudProcessing: true, runSummaries: mode === 'empty_selection' }, summaries: [],
        conversation: { version: 'conversation_v1', id: '11111111-1111-4111-8111-111111111111', approved: true,
          messages: mode === 'follow_up' ? [
            { role: 'user', text: '复盘这次跑步' },
            { role: 'assistant', text: '根据你授权的这条跑步摘要，这次用时三十分钟。' },
          ] : [],
        },
      }, { model, budget: database.budget });
      assert.equal(result.status, 'completed');
      if (result.status === 'completed') assert.equal(result.answer, answer);
      assert.deepEqual(result.actions, [{ kind: 'model_requested', step: 1 }]);
      assert.equal(sent.length, 1);
      assert.deepEqual(sent[0]!.tools, []);
      assert.match(sent[0]!.messages[0]!.content as string, /当前任务没有授权的跑步摘要/);
      if (mode === 'follow_up') {
        assert.equal(sent[0]!.messages.length, 4);
        assert.match(sent[0]!.messages[2]!.content as string, /用时三十分钟/);
      } else {
        assert.equal(sent[0]!.messages.length, 2);
      }
      assert.deepEqual(database.sqlite.prepare(
        'SELECT state, charged_micros FROM agent_budget_reservations',
      ).all().map(row => ({ ...row })), [{ state: 'settled', charged_micros: 78 }]);
    });
  }
});

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
      assert.equal(row.reserved_micros, 7920);
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

for (const finishReason of ['stop', 'length']) {
  test(`三轮复盘统一使用 1600 上限，超过 600 的最终轮按 ${finishReason} 判定完整性`, async (t) => {
    const database = fixture();
    t.after(database.close);
    const sent: ReturnType<typeof parseCountingRequest>[] = [];
    const answer = '根据 run_1，你完成四公里，用时三十分钟；缺少分段数据，无法判断节奏变化。';
    const model = createDeepseekModel({
      apiKey: 'test-only-key',
      countInputTokens: async raw => {
        const body = parseCountingRequest(raw);
        assert.equal(body.max_tokens, 1600);
        sent.push(body);
        return 100;
      },
      fetch: async () => {
        const step = sent.length;
        const outputTokens = step === 3 ? 800 : 40;
        return Response.json({
          usage: { prompt_tokens: 100, completion_tokens: outputTokens, total_tokens: 100 + outputTokens },
          choices: [{ index: 0, finish_reason: step === 3 ? finishReason : 'tool_calls',
            message: step === 3 ? { role: 'assistant', content: answer } : {
              role: 'assistant', content: null, tool_calls: [{ id: `call_${step}`, type: 'function',
                function: step === 1 ? { name: 'runs_list', arguments: '{}' }
                  : { name: 'runs_summary', arguments: '{"runId":"run_1"}' },
              }],
            },
          }],
        });
      },
    });
    const result = await runAgent(task, { model, budget: database.budget });
    if (finishReason === 'stop') {
      assert.equal(result.status, 'completed');
      if (result.status === 'completed') assert.equal(result.answer, answer);
    } else {
      assert.equal(result.status, 'failed');
      assert.equal('reason' in result && result.reason, 'invalid_model_output');
    }
    assert.equal(sent.length, 3);
    assert.equal(result.actions.filter(action => action.kind === 'tool_completed').length, 2);
    assert.deepEqual(database.sqlite.prepare(
      'SELECT reserved_micros, charged_micros FROM agent_budget_reservations ORDER BY request_id',
    ).all().map(row => ({ ...row })), [
      { reserved_micros: 7920, charged_micros: 78 },
      { reserved_micros: 7920, charged_micros: 78 },
      { reserved_micros: 7920, charged_micros: 990 },
    ]);
  });
}
