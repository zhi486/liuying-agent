// 仅本地 HTTPS：测试入口人为延迟回执交付，再真实销毁客户端 socket。
import assert from 'node:assert/strict';
import https from 'node:https';
import { fileURLToPath } from 'node:url';
import { readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { readD1Migrations } from '@cloudflare/vitest-plugin';
import { createDeviceAdmin } from '../src/device-auth.ts';

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const accepted = deferred(); const delivery = deferred();
const modelEntered = deferred(); const modelRelease = deferred();
let calls = 0; let responseEvents = 0; let disconnected; let acceptedSeen = false; let responseStatus;
let outboundSeen = false;
const checked = Date.now();
const bundleDir = fileURLToPath(new URL('../.generated/bundle/', import.meta.url));
const runtime = new Miniflare(convertV4MiniflareOptions({
  host: '127.0.0.1', port: 0, https: true, cf: false,
  telemetry: { enabled: false }, unsafeRegisterWorker: false,
  name: 'local-disconnect-test', modulesRoot: bundleDir,
  modules: [{ type: 'ESModule', path: join(bundleDir, 'network-test-entry.mjs'),
    contents: `import worker, { LiuyingAgentRuntime } from './worker.js';
    export { LiuyingAgentRuntime };
    export default { async fetch(request, env, ctx) {
      const response = await worker.fetch(request, env, ctx);
      if (request.method === 'POST' && new URL(request.url).pathname === '/v1/agent/tasks' && response.status === 202)
        await env.TEST_DELIVERY.fetch('http://fixture/accepted');
      return response;
    } };` },
    { type: 'ESModule', path: join(bundleDir, 'worker.js') },
    ...(await readdir(bundleDir)).filter(name => name.endsWith('.bin')).map(name => ({ type: 'Data', path: join(bundleDir, name) })),
  ],
  compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
  durableObjects: { AGENT_RUNTIME: { className: 'LiuyingAgentRuntime', useSQLite: true } },
  d1Databases: ['DB'],
  bindings: { DEEPSEEK_API_KEY: 'fixture-only-not-a-secret', RATE_CARD_JSON: JSON.stringify({
    version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000,
    outputMicrosPerMillion: 1200000, checkedAtMillis: checked, expiresAtMillis: checked + 3600000,
  }) },
  serviceBindings: { TEST_DELIVERY: async () => {
    acceptedSeen = true; accepted.resolve(); await delivery.promise; return new Response(null, { status: 204 });
  } },
  outboundService: async request => {
    outboundSeen = true;
    assert.equal(request.url, 'https://api.deepseek.com/chat/completions');
    assert.equal(request.method, 'POST');
    calls++; modelEntered.resolve(); await modelRelease.promise;
    return Response.json({ choices: [{ index: 0, finish_reason: 'stop',
      message: { role: 'assistant', content: '本地断连后恢复完成。' } }],
    usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } });
  },
}));

function get(url, header) {
  assert.equal(url.hostname, '127.0.0.1');
  return new Promise((resolve, reject) => {
    // 自签证书例外仅限当前回环连接，不修改全局 TLS 配置。
    const request = https.get(url, { agent: false, rejectUnauthorized: false, headers: { Authorization: header } }, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { body += chunk; });
      response.on('end', () => {
        try { assert.equal(response.statusCode, 200); resolve(JSON.parse(body)); } catch (error) { reject(error); }
      });
    });
    request.on('error', reject);
    request.setTimeout(5000, () => request.destroy(new Error('本地查询超时')));
  });
}

try {
  const base = await runtime.ready;
  const db = await runtime.getD1Database('DB');
  for (const migration of await readD1Migrations(fileURLToPath(new URL('../migrations', import.meta.url))))
    await db.batch(migration.queries.map(sql => db.prepare(sql)));
  const owner = crypto.randomUUID();
  await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device = await createDeviceAdmin({ db }).issue(owner);
  const header = `Bearer ${device.token}`;
  const body = JSON.stringify({ clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '合成断连测试', consent: { cloudProcessing: true, runSummaries: true }, summaries: [] });
  const id = JSON.parse(body).clientRequestId;
  const closed = deferred();
  disconnected = https.request(new URL('/v1/agent/tasks', base), { method: 'POST', agent: false,
    rejectUnauthorized: false, headers: { Authorization: header, 'Content-Type': 'application/json' } });
  disconnected.on('response', response => { responseEvents++; responseStatus = response.statusCode; response.resume(); });
  disconnected.on('error', () => {});
  disconnected.on('close', closed.resolve);
  disconnected.end(body);
  let timeout;
  try {
    await Promise.race([Promise.all([accepted.promise, modelEntered.promise]), new Promise((_, reject) => {
      timeout = setTimeout(() => reject(new Error('本地接单等待超时')), 10000);
    })]);
  } finally { clearTimeout(timeout); }
  assert.equal(responseEvents, 0);
  disconnected.destroy(); await closed.promise;
  delivery.resolve(); modelRelease.resolve();
  let task;
  const end = Date.now() + 10000;
  do {
    ({ task } = await get(new URL(`/v1/agent/requests/${id}`, base), header));
    if (task.status !== 'queued' && task.status !== 'running') break;
    await delay(50);
  } while (Date.now() < end);
  assert.equal(task.status, 'completed');
  assert.equal(calls, 1); assert.equal(responseEvents, 0);
  assert.equal(await db.prepare('SELECT COUNT(*) AS n FROM agent_cloud_tasks').first('n'), 1);
  assert.equal(await db.prepare("SELECT COUNT(*) AS n FROM agent_budget_reservations WHERE state='settled'").first('n'), 1);
  assert.equal(await db.prepare('SELECT COUNT(*) AS n FROM agent_usage_receipts WHERE applied=1').first('n'), 1);
  console.log('本地 HTTPS socket 已真实断开：未收到回执，原请求键恢复完成，模型和结算各一次');
} catch (error) {
  console.error('本地 HTTPS 断连验证失败：', error instanceof Error ? error.message : '未知测试错误');
  const db = await runtime.getD1Database('DB');
  console.error('合成测试阶段：', JSON.stringify({ acceptedSeen, calls, outboundSeen, responseEvents, responseStatus,
    tasks: (await db.prepare('SELECT status,reason FROM agent_cloud_tasks').all()).results,
    reservations: (await db.prepare('SELECT state,dispatch_state FROM agent_budget_reservations').all()).results }));
  process.exitCode = 1;
} finally {
  disconnected?.destroy(); delivery.resolve(); modelRelease.resolve();
  await runtime.dispose();
}
