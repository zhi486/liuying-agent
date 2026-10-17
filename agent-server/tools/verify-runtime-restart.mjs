// 本地故障注入：在收据提交前或后挂起，强制关闭 workerd，再用同一持久化目录恢复。
// 捕捉的回归：重建旧任务导致再次派发、丢失收据、恢复重复计费或延长原截止。
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { readD1Migrations } from '@cloudflare/vitest-plugin';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { recoverFreeExecutions } from '../src/execution-ledger.ts';

const bundleDir = fileURLToPath(new URL('../.generated/bundle/', import.meta.url));
const mode = process.argv[2] ?? 'receipt';
assert.ok(['receipt', 'no-receipt'].includes(mode), '只接受 receipt 或 no-receipt 场景');
const withoutReceipt = mode === 'no-receipt';
// 留存本次虚构测试数据供审查，不清理或覆盖用户的任何目录。
const persistPath = await mkdtemp(fileURLToPath(new URL('../.generated/restart-', import.meta.url)));
const interceptor = stripTypeScriptTypes(await readFile(new URL('../test/d1-interceptor.ts', import.meta.url), 'utf8'));
let receiptCommitted;
const committed = new Promise(resolve => { receiptCommitted = resolve; });
let calls = 0;
let runtime;
const stopping = new AbortController();
let phase = '初始化';
const checked = Date.now();

async function bounded(promise, label, milliseconds = 10000) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label}超时`)), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}

const modules = [
  { type: 'ESModule', path: join(bundleDir, 'restart-test-entry.mjs'), contents: `
    import worker, { LiuyingAgentRuntime } from './worker.js';
    import { interceptD1 } from './test-interceptor.mjs';
    export class RestartFixture extends LiuyingAgentRuntime {
      #incarnation = crypto.randomUUID();
      constructor(ctx, env) {
        super(ctx, { ...env, DB: interceptD1(env.DB, async (sql, phase) => {
          if (phase === (env.TEST_NO_RECEIPT ? 'before' : 'after') && sql.includes('INSERT INTO agent_usage_receipts')) {
            await env.TEST_SIGNAL.fetch('http://fixture/receipt-committed');
            await new Promise(() => {});
          }
        }) });
      }
      probe() {
        return { incarnation: this.#incarnation,
          owner: this.ctx.storage.sql.exec('SELECT * FROM runtime_owner').toArray() };
      }
    }
    export default worker;` },
  { type: 'ESModule', path: join(bundleDir, 'test-interceptor.mjs'), contents: interceptor },
  { type: 'ESModule', path: join(bundleDir, 'worker.js') },
  ...(await readdir(bundleDir)).filter(name => name.endsWith('.bin')).map(name => ({ type: 'Data', path: join(bundleDir, name) })),
];

function start() {
  assert.equal(stopping.signal.aborted, false, '测试已进入收尾，禁止迟到步骤再启动运行时');
  return new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0, cf: false, resourcePersistencePath: persistPath,
    telemetry: { enabled: false }, unsafeRegisterWorker: false,
    name: 'local-restart-test', modulesRoot: bundleDir, modules,
    compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
    durableObjects: { AGENT_RUNTIME: { className: 'RestartFixture', useSQLite: true } },
    d1Databases: ['DB'],
    bindings: { TEST_NO_RECEIPT: withoutReceipt, DEEPSEEK_API_KEY: 'fixture-only-not-a-secret', RATE_CARD_JSON: JSON.stringify({
      version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000,
      outputMicrosPerMillion: 1200000, checkedAtMillis: checked, expiresAtMillis: checked + 3600000,
    }) },
    serviceBindings: { TEST_SIGNAL: async request => {
      assert.equal(request.url, 'http://fixture/receipt-committed');
      receiptCommitted();
      return new Response(null, { status: 204 });
    } },
    outboundService: async request => {
      assert.equal(request.url, 'https://api.deepseek.com/chat/completions');
      assert.equal(request.method, 'POST');
      calls++;
      return Response.json({ choices: [{ index: 0, finish_reason: 'stop',
        message: { role: 'assistant', content: '此答案必须随中断丢弃，不能复活。' } }],
      usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } });
    },
  }));
}

async function send(header, path, body) {
  const response = await bounded(runtime.dispatchFetch(`https://local.invalid${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: header, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), '本地业务请求');
  assert.ok([200, 202].includes(response.status), `本地响应状态 ${response.status}`);
  return (await response.json()).task;
}

async function ledger(db) {
  return (await db.prepare(`SELECT u.applied, u.input_tokens, u.output_tokens,
    r.state, r.dispatch_state, r.charged_micros, r.reserved_micros FROM agent_budget_reservations r
    LEFT JOIN agent_usage_receipts u ON r.id=u.reservation_id`).all()).results;
}

async function verify() {
  runtime = start();
  const oldAddress = await bounded(runtime.ready, '首次启动');
  const db = await runtime.getD1Database('DB');
  for (const migration of await readD1Migrations(fileURLToPath(new URL('../migrations', import.meta.url))))
    await db.batch(migration.queries.map(sql => db.prepare(sql)));
  const owner = crypto.randomUUID();
  await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device = await createDeviceAdmin({ db }).issue(owner);
  const header = `Bearer ${device.token}`;
  const body = { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '合成强制重启测试', consent: { cloudProcessing: true, runSummaries: true }, summaries: [] };
  phase = withoutReceipt ? '收据提交前屏障' : '收据提交后屏障';
  const accepted = await send(header, '/v1/agent/tasks', body);
  await bounded(committed, '收据提交');
  const before = await send(header, `/v1/agent/requests/${body.clientRequestId}`);
  assert.equal(before.id, accepted.id);
  assert.equal(before.status, 'running');
  assert.equal(before.result, null);
  const pending = [{ applied: withoutReceipt ? null : 0, input_tokens: withoutReceipt ? null : 1000,
    output_tokens: withoutReceipt ? null : 20, reserved_micros: 6720,
    state: 'reserved', dispatch_state: 'may_have_dispatched', charged_micros: null }];
  assert.deepEqual(await ledger(db), pending);
  const namespace = await runtime.getDurableObjectNamespace('AGENT_RUNTIME');
  // Miniflare 的 Node 桥返回惰性代理；关闭前取纯数据，不能跨进程复用旧句柄。
  const original = JSON.parse(JSON.stringify(await namespace.get(namespace.idFromName(owner)).probe()));
  assert.deepEqual(original.owner, [{ singleton: 1, owner, version: 1 }]);
  phase = '强制终止';
  assert.ok(Date.now() < before.deadlineAtMillis);
  const started = performance.now();
  // 固定 Miniflare 5 的 Runtime.dispose 直接 SIGKILL；屏障从不释放，不调用优雅驱逐。
  await bounded(runtime.dispose(), '强制终止', 5000);
  runtime = undefined;
  const stoppedMillis = Math.round(performance.now() - started);
  assert.ok(Date.now() < before.deadlineAtMillis, '中断不能等到业务自然超时');
  await assert.rejects(fetch(oldAddress, { signal: AbortSignal.timeout(2000) }));

  phase = '同目录重启';
  runtime = start();
  await bounded(runtime.ready, '重启');
  // 不重建 schema/账户/设备，全部从旧磁盘恢复。
  const restoredDb = await runtime.getD1Database('DB');
  assert.deepEqual(await ledger(restoredDb), pending);
  const replacementNamespace = await runtime.getDurableObjectNamespace('AGENT_RUNTIME');
  const replacement = JSON.parse(JSON.stringify(await replacementNamespace.get(replacementNamespace.idFromName(owner)).probe()));
  assert.notEqual(replacement.incarnation, original.incarnation);
  assert.deepEqual(replacement.owner, original.owner);
  assert.ok(Date.now() < before.deadlineAtMillis, '必须在原执行窗口内验证重放');
  const replay = await send(header, '/v1/agent/tasks', body);
  assert.equal(replay.status, 'running');
  assert.equal(replay.id, before.id);
  assert.equal(replay.deadlineAtMillis, before.deadlineAtMillis);
  assert.equal(replay.result, null);
  assert.equal(calls, 1);
  phase = '可信账务恢复';
  const firstRecovery = await recoverFreeExecutions({ db: restoredDb });
  assert.equal(firstRecovery.receiptsApplied, withoutReceipt ? 0 : 1);
  assert.equal(firstRecovery.closed, 0);
  const recovered = withoutReceipt ? pending : [{ ...pending[0], applied: 1, state: 'settled', charged_micros: 324 }];
  assert.deepEqual(await ledger(restoredDb), recovered);
  assert.equal((await recoverFreeExecutions({ db: restoredDb })).receiptsApplied, 0);
  assert.deepEqual(await ledger(restoredDb), recovered);
  phase = '原截止收敛';
  console.log(`强制终止耗时 ${stoppedMillis} 毫秒；${withoutReceipt ? '无持久收据，预留未释放' : '新实例已恢复同一收据'}，正在核对原截止。`);
  await delay(Math.max(0, before.deadlineAtMillis - Date.now()) + 50, undefined, { signal: stopping.signal });
  const expired = await send(header, `/v1/agent/requests/${body.clientRequestId}`);
  assert.equal(expired.status, 'timed_out');
  assert.equal(expired.result, null);
  const final = await send(header, '/v1/agent/tasks', body);
  assert.equal(final.id, before.id);
  assert.equal(final.status, 'timed_out');
  assert.equal(final.deadlineAtMillis, before.deadlineAtMillis);
  assert.equal(calls, 1);
  for (let attempt = 0; attempt < 2; attempt++) {
    const recovery = await recoverFreeExecutions({ db: restoredDb });
    assert.equal(recovery.receiptsApplied, 0);
    assert.equal(recovery.closed, 0);
    assert.deepEqual(await ledger(restoredDb), withoutReceipt ? [{ ...pending[0], state: 'uncertain' }] : recovered);
  }
  assert.equal(await restoredDb.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks').first('n'), 1);
  assert.equal(await restoredDb.prepare('SELECT COUNT(*) AS n FROM agent_budget_reservations').first('n'), 1);
  assert.equal(await restoredDb.prepare('SELECT COUNT(*) AS n FROM agent_usage_receipts').first('n'), withoutReceipt ? 0 : 1);
  console.log(`本地强制重启通过（${mode}）：原键不重跑、原截止不延长、答案不复活；${withoutReceipt ? '无收据保留 6720 微美元未知预留，不退款' : '收据只结算一次'}。`);
}

try {
  // 包括 SQL、RPC 桥、正文读取和恢复在内的所有阶段共享场景上限。
  await bounded(verify(), '完整重启场景', 45000);
} catch (error) {
  console.error(`本地强制重启验证失败（${phase}）：`, error instanceof Error ? error.message : '未知测试错误');
  process.exitCode = 1;
} finally {
  stopping.abort();
  try {
    if (runtime) await bounded(runtime.dispose(), '最终关闭', 5000);
  } catch (error) {
    console.error('本地运行时收尾失败：', error instanceof Error ? error.message : '未知测试错误');
    process.exitCode = 1;
  } finally {
    console.log(`虚构测试数据库留存：${persistPath}`);
  }
}
