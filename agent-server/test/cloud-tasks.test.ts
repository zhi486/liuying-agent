import assert from 'node:assert/strict';
import { test, type TestContext } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { mkdtempSync, unlinkSync, rmdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { DATABASE_TIME_SQL } from '../src/cloud-task-contracts.ts';

const start = Date.UTC(2026, 8, 14);
const answer = {
  status: 'completed',
  answer: '这次跑步的总结。',
  actions: [{ kind: 'model_requested', step: 1 }],
};
const input = () => ({
  clientRequestId: crypto.randomUUID(),
  localTaskId: crypto.randomUUID(),
  prompt: '分析这次跑步',
  consent: { cloudProcessing: true, runSummaries: false },
  summaries: [],
});

test('未替换时钟的 SQLite 返回真实毫秒时间', (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  const before = Date.now();
  const observed = Number(
    database.sqlite.prepare(`SELECT ${DATABASE_TIME_SQL} AS observed`).get()!.observed,
  );
  assert.ok(Number.isSafeInteger(observed));
  assert.ok(observed >= before && observed <= Date.now());
});

test('测试数据库时钟保留两位秒和完整三位毫秒', (t) => {
  let time = start;
  const database = openBudgetDatabase(':memory:', () => time);
  t.after(database.close);
  for (const offset of [0, 1, 100, 999, 9100, 9999, 10999]) {
    time = start + offset;
    const observed = database.sqlite
      .prepare(`SELECT ${DATABASE_TIME_SQL} AS observed, strftime('%f','now') AS fraction`)
      .get()!;
    assert.equal(observed.observed, time);
    assert.match(String(observed.fraction), /^\d{2}\.\d{3}$/);
  }
});

test('SQL 排队跨过截止时刻不能完成，跨过结果到期时刻不能读取正文', async (t) => {
  const { tasks, first, database, now, setTime } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  let advance = 0;
  const delayed = createCloudTasks({
    now,
    db: {
      prepare: database.db.prepare,
      async batch(statements) {
        setTime(now() + advance);
        return database.db.batch(statements);
      },
    },
  });
  setTime(start + 29999);
  advance = 2;
  await assert.rejects(delayed.finish(execution, answer), /inactive/);
  assert.equal((await tasks.get(first.header, task.id)).status, 'timed_out');
  const next = await tasks.accept(first.header, input());
  const nextExecution = await tasks.claim(first.header, next.task.id);
  await tasks.finish(nextExecution, answer);
  const expires = now() + 86400000;
  setTime(expires - 1);
  const expired = await delayed.get(first.header, next.task.id);
  assert.equal(expired.resultState, 'expired');
  assert.equal(expired.result, null);
});

test('结果在数据库回包等待期间到期，返回前同样隐藏正文', async (t) => {
  const { tasks, first, database, now, setTime } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  await tasks.finish(execution, answer);
  const delayed = createCloudTasks({
    now,
    db: {
      prepare: database.db.prepare,
      async batch(statements) {
        const results = await database.db.batch(statements);
        setTime(now() + 2);
        return results;
      },
    },
  });
  setTime(start + 86400000 - 1);
  const result = await delayed.get(first.header, task.id);
  assert.equal(result.resultState, 'expired');
  assert.equal(result.result, null);
});

test('清理语句与最终读取之间跨过结果到期，也按读取时的数据库时钟隐藏', async (t) => {
  let applicationTime = start;
  let sqlTime = start;
  const database = openBudgetDatabase(':memory:', () => sqlTime);
  t.after(database.close);
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner_1', 13440, 0)");
  const device = await createDeviceAdmin({ db: database.db, now: () => applicationTime }).issue(
    'owner_1',
  );
  const header = `Bearer ${device.token}`;
  const tasks = createCloudTasks({ db: database.db, now: () => applicationTime });
  const { task } = await tasks.accept(header, input());
  const execution = await tasks.claim(header, task.id);
  applicationTime += 1000;
  sqlTime = applicationTime;
  await tasks.finish(execution, answer);
  database.sqlite.function('advance_clock', () => {
    sqlTime += 1001;
    return 0;
  });
  database.sqlite
    .exec(`CREATE TRIGGER delay_digest AFTER UPDATE OF payload_digest ON agent_cloud_tasks
    BEGIN SELECT advance_clock(); END`);
  applicationTime = start + 86400000;
  sqlTime = applicationTime;
  const result = await tasks.get(header, task.id);
  assert.equal(result.resultState, 'expired');
  assert.equal(result.result, null);
});

test('认证之后、SQL 执行之前撤销，所有业务操作仍被数据库门禁挡住', async (t) => {
  for (const operation of [
    'accept',
    'get',
    'findByRequest',
    'cancel',
    'withdraw',
    'acknowledge',
    'claim',
    'finish',
  ] as const) {
    const { database, tasks, admin, first, now } = await setup(t);
    const request = input();
    const { task } = await tasks.accept(first.header, request);
    const execution = await tasks.claim(first.header, task.id);
    if (operation === 'get' || operation === 'acknowledge') await tasks.finish(execution, answer);
    const guarded = createCloudTasks({
      now,
      db: {
        prepare: database.db.prepare,
        async batch(statements) {
          await admin.revoke('owner_1', first.deviceId);
          return database.db.batch(statements);
        },
      },
    });
    const call =
      operation === 'accept'
        ? () => guarded.accept(first.header, input())
        : operation === 'findByRequest'
          ? () => guarded.findByRequest(first.header, request.clientRequestId)
          : operation === 'finish'
            ? () => guarded.finish(execution, answer)
            : () => guarded[operation](first.header, task.id);
    await assert.rejects(call, operation === 'finish' ? /inactive/ : /unauthenticated/);
    assert.equal(
      database.sqlite.prepare('SELECT COUNT(*) AS count FROM agent_cloud_tasks').get()!.count,
      1,
    );
    const row = database.sqlite
      .prepare('SELECT * FROM agent_cloud_tasks WHERE id = ?')
      .get(task.id)!;
    if (operation === 'get' || operation === 'acknowledge')
      assert.equal(row.result_state, 'available');
    else assert.equal(row.status, 'cancelled');
  }
});

test('并发取消与完成只保留首次终态，后来的操作不能复活或覆盖', async (t) => {
  for (const finishFirst of [false, true]) {
    const { tasks, first } = await setup(t);
    const { task } = await tasks.accept(first.header, input());
    const execution = await tasks.claim(first.header, task.id);
    const operations = [
      () => tasks.cancel(first.header, task.id),
      () => tasks.finish(execution, answer),
    ];
    if (finishFirst) operations.reverse();
    const results = await Promise.allSettled(operations.map((operation) => operation()));
    const final = await tasks.get(first.header, task.id);
    assert.ok(final.status === 'cancelled' || final.status === 'completed');
    assert.equal(
      results.filter((result) => result.status === 'rejected').length,
      final.status === 'cancelled' ? 1 : 0,
    );
    assert.deepEqual(final.result, final.status === 'completed' ? answer : null);
    assert.deepEqual(await tasks.cancel(first.header, task.id), final);
  }
});

test('原设备到期后不能认领或写入，新设备仍可查询同账户的已完成任务', async (t) => {
  const { tasks, first, setTime, device } = await setup(t);
  setTime(first.expiresAtMillis - 1000);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  await tasks.finish(execution, answer);
  const next = await tasks.accept(first.header, input());
  const nextExecution = await tasks.claim(first.header, next.task.id);
  const second = await device();
  setTime(first.expiresAtMillis);
  await assert.rejects(tasks.get(first.header, task.id), /unauthenticated/);
  await assert.rejects(tasks.claim(first.header, next.task.id), /unauthenticated/);
  await assert.rejects(tasks.claim(second.header, next.task.id), /inactive/);
  await assert.rejects(tasks.finish(nextExecution, answer), /inactive/);
  assert.deepEqual((await tasks.get(second.header, task.id)).result, answer);
});

test('新增迁移保留原账户、熔断与已结算预算记录', () => {
  const sqlite = new DatabaseSync(':memory:');
  try {
    sqlite.exec(
      readFileSync(new URL('../migrations/0001_agent_budget.sql', import.meta.url), 'utf8'),
    );
    sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner_1', 10000, 1)");
    sqlite.exec(`INSERT INTO agent_budget_reservations VALUES
      ('reservation', 'owner_1', 'task', 'task:1', '2026-09', 'deepseek-flash', 'rate', 300000, 1200000,
      6720, 'settled', 42, 100, 10, 0, 1000)`);
    const accounts = sqlite.prepare('SELECT * FROM agent_budget_accounts').all();
    const reservations = sqlite.prepare('SELECT * FROM agent_budget_reservations').all();
    const migration = readFileSync(
      new URL('../migrations/0002_agent_device_tasks.sql', import.meta.url),
      'utf8',
    );
    sqlite.exec(migration);
    sqlite.exec(migration);
    assert.deepEqual(sqlite.prepare('SELECT * FROM agent_budget_accounts').all(), accounts);
    assert.deepEqual(sqlite.prepare('SELECT * FROM agent_budget_reservations').all(), reservations);
  } finally {
    sqlite.close();
  }
});

test('有效摘要与六条工具行动可往返，修改摘要或授权不能重用请求键', async (t) => {
  const { tasks, first } = await setup(t);
  const summary = {
    runId: 'run_1',
    distanceMeters: 5000,
    durationSeconds: 1800,
    averagePaceSecPerKm: 360,
    averageHeartRate: 150,
  };
  const request = {
    ...input(),
    consent: { cloudProcessing: true, runSummaries: true },
    summaries: [summary],
  };
  const { task } = await tasks.accept(first.header, request);
  await assert.rejects(
    tasks.accept(first.header, { ...request, summaries: [{ ...summary, distanceMeters: 4000 }] }),
    /conflict/,
  );
  await assert.rejects(
    tasks.accept(first.header, {
      ...request,
      consent: { cloudProcessing: true, runSummaries: false },
      summaries: [],
    }),
    /conflict/,
  );
  const execution = await tasks.claim(first.header, task.id);
  const result = {
    ...answer,
    actions: [
      { kind: 'model_requested', step: 1 },
      ...Array.from({ length: 3 }, () => ({
        kind: 'tool_completed',
        step: 1,
        tool: 'runs.summary',
      })),
      { kind: 'model_requested', step: 2 },
      ...Array.from({ length: 3 }, () => ({
        kind: 'tool_completed',
        step: 2,
        tool: 'runs.summary',
      })),
      { kind: 'model_requested', step: 3 },
    ],
  };
  await tasks.finish(execution, result);
  assert.deepEqual((await tasks.get(first.header, task.id)).result, result);
});

test('只能由原设备认领一次，执行秘密只保存摘要', async (t) => {
  const { tasks, first, device, database } = await setup(t);
  const second = await device();
  const other = await device('owner_2');
  const accepted = await tasks.accept(first.header, input());
  const id = accepted.task.id;
  await assert.rejects(tasks.claim(other.header, id), /not_found/);
  await assert.rejects(tasks.claim(second.header, id), /inactive/);
  const claimed = await Promise.allSettled([
    tasks.claim(first.header, id),
    tasks.claim(first.header, id),
  ]);
  assert.equal(claimed.filter((result) => result.status === 'fulfilled').length, 1);
  const execution = claimed.find((result) => result.status === 'fulfilled')!;
  assert.equal(execution.status, 'fulfilled');
  assert.equal((await tasks.get(first.header, id)).status, 'running');
  const raw = JSON.stringify(database.sqlite.prepare('SELECT * FROM agent_cloud_tasks').all());
  assert.equal(raw.includes(execution.value.token), false);
  await assert.rejects(tasks.finish({ taskId: id, token: '0'.repeat(64) }, answer), /inactive/);
});

test('完成结果可按请求键恢复，相同结果幂等且不同结果不能覆盖', async (t) => {
  const { tasks, first, setTime } = await setup(t);
  const request = input();
  const { task } = await tasks.accept(first.header, request);
  const execution = await tasks.claim(first.header, task.id);
  setTime(start + 1000);
  await tasks.finish(execution, answer);
  const completed = await tasks.findByRequest(first.header, request.clientRequestId);
  assert.equal(completed.status, 'completed');
  assert.equal(completed.resultState, 'available');
  assert.deepEqual(completed.result, answer);
  setTime(start + 2000);
  await tasks.finish(execution, answer);
  assert.deepEqual(await tasks.get(first.header, task.id), completed);
  await assert.rejects(tasks.finish(execution, { ...answer, answer: '不能替换' }), /conflict/);
  assert.deepEqual(await tasks.cancel(first.header, task.id), completed);
});

test('取消不可被迟到完成覆盖，重复取消幂等，新设备可以取消本账户任务', async (t) => {
  const { tasks, first, device, setTime } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  const second = await device();
  const other = await device('owner_2');
  await assert.rejects(tasks.cancel(other.header, task.id), /not_found/);
  setTime(start + 100);
  const cancelled = await tasks.cancel(second.header, task.id);
  assert.equal(cancelled.status, 'cancelled');
  assert.equal(cancelled.reason, 'cancelled');
  setTime(start + 200);
  assert.deepEqual(await tasks.cancel(first.header, task.id), cancelled);
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
  await assert.rejects(tasks.claim(first.header, task.id), /inactive/);
  assert.equal((await tasks.get(first.header, task.id)).result, null);
});

test('设备撤销和任务取消事务一致，撤销后原执行权不可写结果', async (t) => {
  const { tasks, first, device, admin, database } = await setup(t);
  const second = await device();
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  database.sqlite.exec(
    "CREATE TRIGGER reject_cancel BEFORE UPDATE ON agent_cloud_tasks BEGIN SELECT RAISE(ABORT, '秘密错误'); END",
  );
  await assert.rejects(admin.revoke('owner_1', first.deviceId), /^Error: unavailable$/);
  assert.equal((await tasks.get(first.header, task.id)).status, 'running');
  database.sqlite.exec('DROP TRIGGER reject_cancel');
  await admin.revoke('owner_1', first.deviceId);
  await assert.rejects(tasks.get(first.header, task.id), /unauthenticated/);
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
  const cancelled = await tasks.get(second.header, task.id);
  assert.equal(cancelled.status, 'cancelled');
  assert.equal(cancelled.reason, 'device_revoked');
});

test('30 秒边界前可以完成，边界上收敛超时且不能认领或完成', async (t) => {
  const { tasks, first, setTime } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  setTime(start + 30000);
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
  assert.equal((await tasks.get(first.header, task.id)).status, 'timed_out');
  const next = await tasks.accept(first.header, input());
  setTime(start + 60000);
  await assert.rejects(tasks.claim(first.header, next.task.id), /inactive/);
  const last = await tasks.accept(first.header, input());
  const lastExecution = await tasks.claim(first.header, last.task.id);
  setTime(start + 89999);
  await tasks.finish(lastExecution, answer);
  assert.equal((await tasks.get(first.header, last.task.id)).status, 'completed');
});

test('结果自终态起保留 24 小时，指纹按接单时间清除，旧键不能重开', async (t) => {
  const { tasks, first, setTime, database } = await setup(t);
  const request = input();
  const { task } = await tasks.accept(first.header, request);
  const execution = await tasks.claim(first.header, task.id);
  setTime(start + 1000);
  await tasks.finish(execution, answer);
  setTime(start + 86400000);
  assert.equal((await tasks.get(first.header, task.id)).resultState, 'available');
  assert.equal(
    database.sqlite
      .prepare('SELECT payload_digest FROM agent_cloud_tasks WHERE id = ?')
      .get(task.id)!.payload_digest,
    null,
  );
  await assert.rejects(tasks.accept(first.header, request), /request_expired/);
  setTime(start + 86401000);
  const expired = await tasks.get(first.header, task.id);
  assert.equal(expired.resultState, 'expired');
  assert.equal(expired.result, null);
  assert.equal(expired.status, 'completed');
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
});

test('确认本地保存仅允许终态并立即清空，重复确认不恢复正文', async (t) => {
  const { tasks, first } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  await assert.rejects(tasks.acknowledge(first.header, task.id), /inactive/);
  const execution = await tasks.claim(first.header, task.id);
  await tasks.finish(execution, answer);
  const acknowledged = await tasks.acknowledge(first.header, task.id);
  assert.equal(acknowledged.resultState, 'acknowledged');
  assert.equal(acknowledged.result, null);
  assert.deepEqual(await tasks.acknowledge(first.header, task.id), acknowledged);
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
});

test('撤回授权取消活跃任务，已完成任务只清除结果而不改变终态', async (t) => {
  const { tasks, first } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const withdrawn = await tasks.withdraw(first.header, task.id);
  assert.equal(withdrawn.status, 'cancelled');
  assert.equal(withdrawn.reason, 'consent_revoked');
  assert.equal(withdrawn.resultState, 'withdrawn');
  const next = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, next.task.id);
  await tasks.finish(execution, answer);
  const terminal = await tasks.withdraw(first.header, next.task.id);
  assert.equal(terminal.status, 'completed');
  assert.equal(terminal.resultState, 'withdrawn');
  assert.equal(terminal.result, null);
  assert.deepEqual(await tasks.withdraw(first.header, next.task.id), terminal);
  await assert.rejects(tasks.finish(execution, answer), /inactive/);
});

test('非法结果与行动记录在写库前拒绝，保持 running', async (t) => {
  const { tasks, first } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  const execution = await tasks.claim(first.header, task.id);
  const invalid = [
    null,
    { ...answer, ownerId: 'owner_2' },
    { ...answer, answer: '' },
    { ...answer, answer: 'x'.repeat(4001) },
    { status: 'completed', reason: 'cancelled', actions: [] },
    { status: 'failed', reason: 'deadline', actions: [] },
    { status: 'cancelled', reason: 'device_revoked', actions: [] },
    { ...answer, actions: [{ kind: 'model_requested', step: 4 }] },
    { ...answer, actions: [{ kind: 'tool_completed', step: 1, tool: 'runs.list' }] },
    { ...answer, actions: [{ kind: 'model_requested', step: 1, tool: 'runs.list' }] },
    {
      ...answer,
      actions: [
        { kind: 'model_requested', step: 1 },
        { kind: 'tool_completed', step: 1, tool: 'arbitrary' },
      ],
    },
    { ...answer, actions: Array(10).fill({ kind: 'model_requested', step: 1 }) },
  ];
  for (const result of invalid)
    await assert.rejects(tasks.finish(execution, result), /invalid_request/);
  assert.equal((await tasks.get(first.header, task.id)).status, 'running');
  await tasks.finish(execution, { status: 'blocked', reason: 'budget_denied', actions: [] });
  assert.equal((await tasks.get(first.header, task.id)).reason, 'budget_denied');
});

test('数据库重开后保留结果与认领墓碑，维护清理无需手机查询', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'liuying-cloud-'));
  const path = join(directory, 'tasks.sqlite');
  let time = start;
  let database = openBudgetDatabase(path, () => time);
  t.after(() => {
    database.close();
    unlinkSync(path);
    rmdirSync(directory);
  });
  database.sqlite
    .prepare('INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, 13440)')
    .run('owner_1');
  const device = await createDeviceAdmin({ db: database.db, now: () => time }).issue('owner_1');
  const header = `Bearer ${device.token}`;
  let tasks = createCloudTasks({ db: database.db, now: () => time });
  const { task } = await tasks.accept(header, input());
  const execution = await tasks.claim(header, task.id);
  await tasks.finish(execution, answer);
  const active = await tasks.accept(header, input());
  await tasks.claim(header, active.task.id);
  database.close();
  database = openBudgetDatabase(path, () => time);
  tasks = createCloudTasks({ db: database.db, now: () => time });
  assert.deepEqual((await tasks.get(header, task.id)).result, answer);
  await assert.rejects(tasks.claim(header, active.task.id), /inactive/);
  time += 86400000;
  await tasks.purge();
  assert.equal(
    database.sqlite.prepare('SELECT result_json FROM agent_cloud_tasks WHERE id = ?').get(task.id)!
      .result_json,
    null,
  );
  assert.equal((await tasks.get(header, active.task.id)).status, 'timed_out');
});

test('SQL 约束拒绝 NULL 状态理由与缺少状态的可用结果', async (t) => {
  const { tasks, first, database } = await setup(t);
  const { task } = await tasks.accept(first.header, input());
  for (const sql of [
    "status = 'cancelled', reason = NULL",
    "status = 'timed_out', reason = NULL",
    "status = 'completed', result_state = 'available', result_json = '{}', result_expires_at = 1",
  ]) {
    assert.throws(
      () =>
        database.sqlite.prepare(`UPDATE agent_cloud_tasks SET ${sql} WHERE id = ?`).run(task.id),
      /CHECK constraint failed/,
    );
  }
});

async function setup(t: TestContext) {
  let time = start;
  const database = openBudgetDatabase(':memory:', () => time);
  t.after(database.close);
  const now = () => time;
  const admin = createDeviceAdmin({ db: database.db, now });
  async function device(owner = 'owner_1') {
    database.sqlite
      .prepare(
        'INSERT OR IGNORE INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, 13440)',
      )
      .run(owner);
    const credential = await admin.issue(owner);
    return { ...credential, header: `Bearer ${credential.token}` };
  }
  return {
    database,
    admin,
    device,
    first: await device(),
    tasks: createCloudTasks({ db: database.db, now }),
    now,
    setTime: (value: number) => {
      time = value;
    },
  };
}

test('通过认证的接单持久化为 queued 并可以按 ID 查询', async (t) => {
  const database = openBudgetDatabase(':memory:', () => start);
  t.after(database.close);
  database.sqlite
    .prepare('INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, 13440)')
    .run('owner_1');
  const device = await createDeviceAdmin({ db: database.db, now: () => start }).issue('owner_1');
  const tasks = createCloudTasks({ db: database.db, now: () => start });
  const accepted = await tasks.accept(`Bearer ${device.token}`, input());
  assert.equal(accepted.task.status, 'queued');
  assert.equal(accepted.created, true);
  assert.deepEqual(await tasks.get(`Bearer ${device.token}`, accepted.task.id), accepted.task);
});

test('同键并发只接单一次，规范化内容去重，异文和不同本地任务拒绝', async (t) => {
  const { tasks, first } = await setup(t);
  const request = input();
  const results = await Promise.all(
    Array.from({ length: 8 }, () => tasks.accept(first.header, request)),
  );
  assert.equal(results.filter((result) => result.created).length, 1);
  assert.equal(new Set(results.map((result) => result.task.id)).size, 1);
  const duplicate = await tasks.accept(first.header, { ...request, prompt: ` ${request.prompt} ` });
  assert.equal(duplicate.created, false);
  for (const change of [{ prompt: '另一个问题' }, { localTaskId: crypto.randomUUID() }]) {
    await assert.rejects(tasks.accept(first.header, { ...request, ...change }), /conflict/);
  }
});

test('同账户多设备共享活跃限制与查询，不同账户相互隔离', async (t) => {
  const { tasks, first, device } = await setup(t);
  const second = await device();
  const other = await device('owner_2');
  const request = input();
  const accepted = await tasks.accept(first.header, request);
  await assert.rejects(tasks.accept(second.header, input()), /busy/);
  assert.deepEqual(
    await tasks.findByRequest(second.header, request.clientRequestId),
    accepted.task,
  );
  await assert.rejects(tasks.get(other.header, accepted.task.id), /not_found/);
  await assert.rejects(tasks.findByRequest(other.header, request.clientRequestId), /not_found/);
  assert.equal((await tasks.accept(other.header, request)).created, true);
});

test('输入拒绝额外身份、畸形 UUID、未授权内容和超限，数据库不留正文', async (t) => {
  const { tasks, first, database } = await setup(t);
  for (const change of [
    { ownerId: 'owner_2' },
    { payloadDigest: 'client' },
    { clientRequestId: 'not-uuid' },
    { localTaskId: crypto.randomUUID().toUpperCase() },
    { prompt: ' ' },
    { prompt: 'x'.repeat(2001) },
    { consent: { cloudProcessing: false, runSummaries: false } },
    { summaries: [{}] },
  ]) {
    await assert.rejects(tasks.accept(first.header, { ...input(), ...change }), /invalid_request/);
  }
  const accepted = await tasks.accept(first.header, input());
  assert.deepEqual(
    Object.keys(accepted.task).sort(),
    [
      'id',
      'status',
      'reason',
      'acceptedAtMillis',
      'deadlineAtMillis',
      'updatedAtMillis',
      'resultState',
      'result',
    ].sort(),
  );
  const storage = JSON.stringify(database.sqlite.prepare('SELECT * FROM agent_cloud_tasks').all());
  assert.equal(storage.includes('分析这次跑步'), false);
  assert.equal(storage.includes(first.token), false);
});

test('接单截止时间固定 30 秒，重复不续期，超时查询后允许新任务但不复活旧键', async (t) => {
  const { tasks, first, setTime } = await setup(t);
  const request = input();
  const accepted = await tasks.accept(first.header, request);
  setTime(start + 29999);
  assert.equal((await tasks.accept(first.header, request)).task.deadlineAtMillis, start + 30000);
  setTime(start + 30000);
  assert.equal((await tasks.get(first.header, accepted.task.id)).status, 'timed_out');
  assert.equal((await tasks.accept(first.header, input())).created, true);
  assert.equal((await tasks.accept(first.header, request)).task.status, 'timed_out');
});
