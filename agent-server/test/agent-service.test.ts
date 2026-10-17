import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fixture, input, rate, start } from './execution-fixture.ts';

import * as loaded from '../src/agent-service.ts';
import { interceptD1 } from './d1-interceptor.ts';
const reply = (tool = false) => ({
  id: 'completion_fixture',
  object: 'chat.completion',
  created: 1789430400,
  model: 'deepseek-flash',
  system_fingerprint: 'fixture',
  choices: [
    {
      index: 0,
      logprobs: null,
      finish_reason: tool ? 'tool_calls' : 'stop',
      message: tool
        ? {
            role: 'assistant',
            content: null,
            tool_calls: [
              {
                id: 'call_1',
                type: 'function',
                function: {
                  name: 'runs_summary',
                  arguments: '{"runId":"run_1"}',
                },
              },
            ],
          }
        : { role: 'assistant', content: '根据 run_1，你完成了四公里。' },
    },
  ],
  usage: {
    prompt_tokens: 100,
    completion_tokens: 40,
    total_tokens: 140,
    prompt_cache_hit_tokens: 0,
    prompt_cache_miss_tokens: 100,
    prompt_tokens_details: { cached_tokens: 0 },
  },
});

test('正式服务把会话历史送给模型并纳入同请求去重指纹', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const sent: string[] = [];
  const service = loaded.createAgentService({ db: f.database.db, now: f.now, rate,
    apiKey: 'test-only-key', countInputTokens: async () => 100,
    fetch: async (_: unknown, init?: RequestInit) => { sent.push(String(init!.body)); return Response.json(reply()); },
  });
  const request = { ...input(), conversation: { version: 'conversation_v1',
    id: '77777777-7777-4777-8777-777777777777', approved: true,
    messages: [{ role: 'user', text: '我喜欢夜跑' }, { role: 'assistant', text: '我们晚上跑' }] } };
  const prepared = await service.prepare(f.header, request);
  assert.ok(prepared.run);
  const changed = structuredClone(request);
  changed.conversation.messages[0]!.text = '修改之前的消息';
  await assert.rejects(service.prepare(f.header, changed), /conflict/);
  await prepared.run!();
  const messages = JSON.parse(sent[0]!).messages;
  assert.ok(messages.some((m: { role: string; content: string }) => m.role === 'user' && m.content === '我喜欢夜跑'));
  assert.ok(messages.some((m: { role: string; content: string }) => m.role === 'assistant' && m.content === '我们晚上跑'));
});

test('服务冻结输入、仅首次接单可执行，两轮真实协议与账单全部关联云 ID', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const sent: string[] = [];
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async (_: unknown, init?: RequestInit) => {
      sent.push(String(init!.body));
      return Response.json(reply(sent.length === 1));
    },
  });
  const original = input();
  const retry = structuredClone(original);
  const pending = service.prepare(f.header, original);
  original.summaries[0]!.distanceMeters = 999999;
  original.prompt = '调用方事后篡改';
  const prepared = await pending;
  assert.ok(prepared.run);
  assert.notEqual(prepared.task.id, original.localTaskId);
  assert.equal((await service.prepare(f.header, retry)).run, null);
  const running = prepared.run!();
  await assert.rejects(prepared.run!(), /conflict/);
  assert.equal(await running, undefined);
  assert.equal(sent.length, 2);
  assert.ok(!sent[0]!.includes('4000'));
  assert.ok(sent[1]!.includes('4000'));
  assert.ok(!sent.join('').includes('999999'));
  const rows = f.database.sqlite
    .prepare(
      'SELECT task_id,request_id,state,charged_micros FROM agent_budget_reservations ORDER BY request_id',
    )
    .all();
  assert.deepEqual(
    rows.map((r) => ({ ...r })),
    [1, 2].map((step) => ({
      task_id: prepared.task.id,
      request_id: `${prepared.task.id}:${step}`,
      state: 'settled',
      charged_micros: 78,
    })),
  );
  const saved = await service.findByRequest(f.header, retry.clientRequestId);
  assert.equal(saved.status, 'completed');
  assert.ok(saved.result?.status === 'completed');
  assert.equal(saved.result.answer, '根据 run_1，你完成了四公里。');
  assert.deepEqual(saved.result.cost, { currency: 'USD', estimatedMicros: 156,
    inputTokens: 200, outputTokens: 80, usageComplete: true, rateStatus: 'current' });
  assert.deepEqual(
    saved.result.actions.map((a) => a.kind),
    ['model_requested', 'tool_completed', 'model_requested'],
  );
  await service.acknowledge(f.header, prepared.task.id);
  assert.equal((await service.get(f.header, prepared.task.id)).result, null);
});

test('旧费率与旧预算熔断不阻止服务，重复键仍只派发一次并回传成本', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  f.database.sqlite.exec('UPDATE agent_budget_accounts SET monthly_limit_micros=1, halted=1');
  let sent = 0;
  const service = loaded.createAgentService({ db: f.database.db, now: f.now,
    rate: { ...rate, checkedAtMillis: start - 86400000, expiresAtMillis: start },
    apiKey: 'fixture', countInputTokens: async () => 100,
    fetch: async () => { sent++; return Response.json(reply()); } });
  const request = input(); const prepared = await service.prepare(f.header, request);
  await prepared.run!();
  assert.equal((await service.prepare(f.header, request)).run, null);
  const saved = await service.get(f.header, prepared.task.id);
  assert.equal(saved.status, 'completed'); assert.equal(sent, 1);
  assert.equal(saved.result?.cost?.estimatedMicros, 78);
  assert.equal(saved.result?.cost?.rateStatus, 'stale');
});

test('排队消耗原截止，计数跨过 30 秒不能发送 HTTP', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => {
      f.setTime(start + 30001);
      return 100;
    },
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  f.setTime(start + 29000);
  await prepared.run!();
  assert.equal(sent, 0);
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'timed_out');
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'released',
  );
});

test('在输入计数中取消，迟到计数不能发请求，已预留额度安全释放', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let began!: () => void;
  const started = new Promise<void>((resolve) => {
    began = resolve;
  });
  let release!: (value: number) => void;
  const counter = new Promise<number>((resolve) => {
    release = resolve;
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: () => {
      began();
      return counter;
    },
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  const running = prepared.run!();
  await started;
  await service.cancel(f.header, prepared.task.id);
  await running;
  release(100);
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(sent, 0);
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'cancelled');
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'released',
  );
});

test('调用方修改返回 DTO 不能替换执行闭包的任务身份', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  const id = prepared.task.id;
  Object.assign(prepared.task, {
    id: f.execution.taskId,
    deadlineAtMillis: start + 900000,
  });
  await prepared.run!();
  assert.equal(sent, 1);
  assert.equal((await service.get(f.header, id)).status, 'completed');
});

test('单调时钟已耗尽时立即停止，系统时钟回拨不能再认领任务', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let elapsed = 0;
  t.mock.method(performance, 'now', () => elapsed);
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json(reply()),
  });
  const prepared = await service.prepare(f.header, input());
  elapsed = 30001;
  await assert.rejects(prepared.run!(), { message: 'unavailable' });
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'queued');
});

test('收据写入失败不能继续工具，预算保持未知且不返回裸答案', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  f.database.sqlite.exec(
    "CREATE TRIGGER fail_receipt BEFORE INSERT ON agent_usage_receipts BEGIN SELECT RAISE(ABORT, '测试收据故障'); END",
  );
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply(true));
    },
  });
  const prepared = await service.prepare(f.header, input());
  assert.equal(await prepared.run!(), undefined);
  const task = await service.get(f.header, prepared.task.id);
  assert.equal(task.status, 'failed');
  assert.deepEqual(task.result!.actions, [{ kind: 'model_requested', step: 1 }]);
  assert.equal(sent, 1);
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'uncertain',
  );
});

test('最终保存失败只报告静态 unavailable，不能交付内存中的答案', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  f.database.sqlite.exec(
    "CREATE TRIGGER fail_result BEFORE UPDATE OF result_json ON agent_cloud_tasks WHEN NEW.result_json IS NOT NULL BEGIN SELECT RAISE(ABORT, '测试保存故障'); END",
  );
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json(reply()),
  });
  const prepared = await service.prepare(f.header, input());
  await assert.rejects(prepared.run!(), { message: 'unavailable' });
  assert.equal((await service.get(f.header, prepared.task.id)).result, null);
  assert.equal(
    f.database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
  f.setTime(start + 30000);
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'timed_out');
});

test('派发 SQL 许可回包等待中取消，迟到成功也不能发送或退款', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let entered!: () => void;
  const gated = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'after' && sql.includes("SET dispatch_state = 'may_have_dispatched'")) {
      entered();
      await held;
    }
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  const running = prepared.run!();
  await gated;
  await service.cancel(f.header, prepared.task.id);
  await running;
  release();
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(sent, 0);
  assert.equal(
    f.database.sqlite.prepare('SELECT state FROM agent_budget_reservations').get()!.state,
    'uncertain',
  );
});

test('收据 SQL 等待中撤回不复活结果，已知用量仍持久结算', async (t) => {
  const f = await fixture(t);
  const background = new Set<Promise<void>>();
  const waitUntil = (pending: Promise<void>) => {
    background.add(pending);
    void pending.then(() => background.delete(pending));
  };
  await f.tasks.cancel(f.header, f.execution.taskId);
  let entered!: () => void;
  const gated = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.includes('INSERT INTO agent_usage_receipts')) {
      entered();
      await held;
    }
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply(true));
    },
  });
  const prepared = await service.prepare(f.header, input());
  const running = prepared.run!({ waitUntil });
  await gated;
  assert.equal(sent, 1);
  await service.withdraw(f.header, prepared.task.id);
  await running;
  assert.ok(background.size > 0, 'run 已结束，宿主仍必须持有未完成的收据链');
  release();
  await Promise.all([...background]);
  assert.equal(sent, 1);
  assert.equal((await service.get(f.header, prepared.task.id)).resultState, 'withdrawn');
  assert.equal(
    f.database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
});

test('结算后设备被撤销，服务不继续模型或保留答案', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'after' && sql.includes("SET state = 'settled'"))
      await f.admin.revoke('owner', f.device.deviceId);
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply(true));
    },
  });
  const prepared = await service.prepare(f.header, input());
  await assert.rejects(prepared.run!(), { message: 'unavailable' });
  assert.equal(sent, 1);
  const row = f.database.sqlite
    .prepare('SELECT status,result_json FROM agent_cloud_tasks WHERE id = ?')
    .get(prepared.task.id)!;
  assert.equal(row.status, 'cancelled');
  assert.equal(row.result_json, null);
  assert.equal(
    f.database.sqlite.prepare('SELECT charged_micros FROM agent_budget_reservations').get()!
      .charged_micros,
    78,
  );
});

test('并发重复接单只发放一个私密执行闭包', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const submission = input();
  const prepared = await Promise.all([
    service.prepare(f.header, submission),
    service.prepare(f.header, submission),
  ]);
  assert.equal(prepared.filter((p) => p.run !== null).length, 1);
  assert.equal(prepared[0]!.task.id, prepared[1]!.task.id);
  await prepared.find((p) => p.run !== null)!.run!();
  assert.equal(sent, 1);
});

test('派发后网络失败保留未知费用；没有有效收据不能虚构零费用', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let sent = 0;
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      throw new Error('测试私密网络错误');
    },
  });
  const prepared = await service.prepare(f.header, input());
  await prepared.run!();
  const saved = await service.get(f.header, prepared.task.id);
  assert.equal(saved.status, 'failed');
  assert.equal(JSON.stringify(saved).includes('测试私密网络错误'), false);
  assert.equal(sent, 1);
  const row = f.database.sqlite
    .prepare('SELECT state,reserved_micros,charged_micros FROM agent_budget_reservations')
    .get()!;
  assert.equal(row.state, 'uncertain');
  assert.equal(row.reserved_micros, 7920);
  assert.equal(row.charged_micros, null);
  assert.equal(
    f.database.sqlite.prepare('SELECT COUNT(*) AS n FROM agent_usage_receipts').get()!.n,
    0,
  );
});

test('模型后权限检查 SQL 失败立即停止，不凭缓存授权继续下一步', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let sent = 0;
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'before' && sql.includes('AS active') && sent > 0)
      throw new Error('测试检查故障');
  });
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply(true));
    },
  });
  const prepared = await service.prepare(f.header, input());
  await assert.rejects(prepared.run!(), { message: 'unavailable' });
  assert.equal(sent, 1);
  assert.equal((await service.get(f.header, prepared.task.id)).result, null);
});

test('认领数据库一直不回包也受原截止约束，迟到返回不能启动模型', async (t) => {
  const f = await fixture(t);
  const background = new Set<Promise<void>>();
  const waitUntil = (pending: Promise<void>) => {
    background.add(pending);
    void pending.then(() => background.delete(pending));
  };
  await f.tasks.cancel(f.header, f.execution.taskId);
  let entered!: () => void;
  const gated = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (phase === 'after' && sql.includes("SET status = 'running', execution_hash")) {
      entered();
      await held;
    }
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const running = prepared.run!({ waitUntil });
  const rejected = assert.rejects(running, { message: 'unavailable' });
  await gated;
  f.setTime(start + 30001);
  t.mock.timers.tick(30000);
  await rejected;
  assert.equal(background.size, 1, '超时后宿主仍持有原始认领 operation');
  release();
  await Promise.all([...background]);
  assert.equal(sent, 0);
  assert.equal((await service.get(f.header, prepared.task.id)).status, 'timed_out');
});

test('宿主首次拒绝保活时立即停止，终态回读等待期间也不能派发 HTTP', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  let armed = false;
  let authReads = 0;
  let entered!: () => void;
  const gated = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const db = interceptD1(f.database.db, async (sql, phase) => {
    if (
      armed &&
      phase === 'before' &&
      sql.includes('SELECT id, owner_id, expires_at FROM agent_devices')
    ) {
      if (++authReads === 2) {
        entered();
        await held;
      }
    }
  });
  let sent = 0;
  const service = loaded.createAgentService({
    db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => {
      sent++;
      return Response.json(reply());
    },
  });
  const prepared = await service.prepare(f.header, input());
  armed = true;
  const background: Promise<void>[] = [];
  const running = prepared.run!({
    waitUntil: (pending) => {
      background.push(pending);
      if (background.length === 1) throw new Error('测试宿主注册故障');
    },
  });
  const outcome = running.then(
    () => null,
    (error: unknown) => error,
  );
  await gated;
  await background[0];
  const sentDuringRead = sent;
  release();
  const error = await outcome;
  await Promise.all(background);
  assert.equal(sentDuringRead, 0, '终态回读尚未返回时原 operation 已停止');
  assert.equal(background.length, 1);
  assert.ok(error instanceof Error);
  assert.equal(error.message, 'unavailable');
  assert.equal(sent, 0);
  assert.equal((await service.get(f.header, prepared.task.id)).result, null);
});

test('共享服务的保活回调仅属于各自私密 run，不跨请求复用宿主上下文', async (t) => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  const service = loaded.createAgentService({
    db: f.database.db,
    now: f.now,
    rate,
    apiKey: 'test-only-key',
    countInputTokens: async () => 100,
    fetch: async () => Response.json(reply()),
  });
  const first: Promise<void>[] = [];
  const second: Promise<void>[] = [];
  const a = await service.prepare(f.header, input());
  await a.run!({
    waitUntil: (pending) => {
      first.push(pending);
    },
  });
  await Promise.all(first);
  const previousCount = first.length;
  assert.ok(previousCount > 0);
  const b = await service.prepare(f.header, input());
  await b.run!({
    waitUntil: (pending) => {
      second.push(pending);
    },
  });
  await Promise.all(second);
  assert.ok(second.length > 0);
  assert.equal(first.length, previousCount);
  assert.equal((await service.get(f.header, a.task.id)).status, 'completed');
  assert.equal((await service.get(f.header, b.task.id)).status, 'completed');
});
