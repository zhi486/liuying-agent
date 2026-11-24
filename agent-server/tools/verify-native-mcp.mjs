// 隔离模拟器专用：真实 Android HTTPS、Room、Worker、DO 与 SDK；模型和末端插件 I/O 为合成替身。
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:https';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { readD1Migrations } from '@cloudflare/vitest-plugin';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createMcpStore } from '../src/mcp-store.ts';
import { collectMcpCatalog } from '../src/mcp-registry.ts';

const execute = promisify(execFile);
const serial = process.argv[2];
assert.match(serial ?? '', /^emulator-\d+$/, '仅允许明确指定隔离模拟器，不接受真实手机');
const androidSdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
assert.ok(androidSdk, '请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT');
const adb = join(androidSdk, 'platform-tools/adb.exe');
const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/keytool.exe') : 'keytool.exe';
const shell = async (...args) => (await execute(adb, ['-s', serial, ...args], { timeout: 15000 })).stdout.trim();
assert.equal(await shell('shell', 'getprop', 'ro.kernel.qemu'), '1');
assert.equal(await shell('shell', 'settings', 'get', 'global', 'airplane_mode_on'), '1');
assert.equal(await shell('shell', 'settings', 'get', 'global', 'wifi_on'), '0');
assert.equal(await shell('shell', 'ip', 'route'), '', '必须先断开模拟器外部网络');
const directory = await mkdtemp(fileURLToPath(new URL('../.generated/native-mcp-', import.meta.url)));
const bundle = fileURLToPath(new URL('../.generated/bundle/', import.meta.url));
const definition = { name: 'notes.add', inputSchema: { type: 'object', properties: { text: { type: 'string' } },
  required: ['text'], additionalProperties: false } };
const argumentsJson = JSON.stringify({ text: '合成便签，不是真实用户内容' });
const cases = new Map();
const requests = [];
const startedAt = performance.now();
const names = ['read', 'approve', 'deny', 'lose_reply', 'cancel', 'background', 'revoke_private', 'disable_cloud', 'expire', 'read_slow', 'approve_slow', 'cleanup'];
let runtime, server, port, db, reversed = false;
let phase = '启动本地夹具';
const checked = Date.now();
async function bounded(work, label, timeout = 30000) {
  let timer;
  try { return await Promise.race([work, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label}超时`)), timeout);
  })]); } finally { clearTimeout(timer); }
}
try {
  // 每次生成独立短期证书；原生只信任该证书，仍校验 IP/SAN，不安装系统 CA。
  const pfx = join(directory, 'fixture.p12');
  const cert = join(directory, 'fixture.cer');
  await execute(keytool, ['-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
    '-dname', 'CN=127.0.0.1', '-ext', 'SAN=ip:127.0.0.1', '-ext', 'BC=ca:true', '-storetype', 'PKCS12',
    '-keystore', pfx, '-storepass', 'synthetic-fixture', '-keypass', 'synthetic-fixture'], { timeout: 15000 });
  await execute(keytool, ['-exportcert', '-alias', 'fixture', '-keystore', pfx, '-storepass', 'synthetic-fixture', '-file', cert], { timeout: 15000 });
  runtime = new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0, cf: false, resourcePersistencePath: join(directory, 'state'),
    telemetry: { enabled: false }, unsafeRegisterWorker: false,
    name: 'native-mcp-joint-test', modulesRoot: bundle,
    modules: [{ type: 'ESModule', path: join(bundle, 'worker.js') },
      ...(await readdir(bundle)).filter(name => name.endsWith('.bin')).map(name => ({ type: 'Data', path: join(bundle, name) }))],
    compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
    durableObjects: { AGENT_RUNTIME: { className: 'LiuyingAgentRuntime', useSQLite: true } }, d1Databases: ['DB'],
    bindings: { DEEPSEEK_API_KEY: 'fixture-only-not-a-secret', RATE_CARD_JSON: JSON.stringify({
      version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
      checkedAtMillis: checked, expiresAtMillis: checked + 3600000,
    }) },
    outboundService: async request => {
      assert.equal(request.url, 'https://api.deepseek.com/chat/completions', '禁止云端直连插件或真实外部服务');
      assert.equal(request.method, 'POST');
      const body = await request.json();
      const current = [...cases.values()].find(row => body.messages.some(message => message.content === row.prompt));
      assert.ok(current, '模型只允许合成问题');
      assert.ok(!JSON.stringify(body).includes('synthetic-plugin-'), '插件密钥不能进入模型');
      current.models.push(body);
      const first = current.models.length === 1;
      if (!first) {
        const observation = JSON.parse(body.messages.at(-1).content);
        if (current.name === 'deny') assert.deepEqual(observation, { status: 'rejected', reason: 'declined' });
        else if (['lose_reply', 'background', 'revoke_private', 'disable_cloud'].includes(current.name))
          assert.deepEqual(observation, { status: 'unconfirmed', reason: 'unavailable' });
        else assert.equal(observation.result.content[0].text, '手机合成工具结果');
        current.observation = observation.status;
      }
      assert.ok(current.models.length <= 2);
      return Response.json({ choices: [{ index: 0, finish_reason: first ? 'tool_calls' : 'stop', message: {
        role: 'assistant', content: first ? null : '合成联合回答',
        ...(first ? { tool_calls: [{ id: 'plugin_1', type: 'function', function: { name: 'mcp_call',
          arguments: JSON.stringify({ modelName: current.service.grants[0].modelName, argumentsJson }) } }] } : {}),
      } }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
    },
  }));
  await bounded(runtime.ready, 'workerd 启动');
  db = await runtime.getD1Database('DB');
  for (const migration of await readD1Migrations(fileURLToPath(new URL('../migrations', import.meta.url))))
    await db.batch(migration.queries.map(sql => db.prepare(sql)));
  for (const name of names) {
    const ownerId = crypto.randomUUID();
    await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
    const issued = await createDeviceAdmin({ db }).issue(ownerId);
    const store = createMcpStore({ db, principal: { ownerId, deviceId: issued.deviceId },
      discover: async () => collectMcpCatalog(async () => ({ tools: [definition] }), new AbortController().signal) });
    const registered = await store.register('https://tools.example.com/mcp');
    const found = await store.discover(registered.id, registered.revision, new AbortController().signal);
    const service = await store.approve(found.id, found.revision, [{ name: definition.name,
      mode: ['approve', 'approve_slow', 'deny', 'cancel', 'expire'].includes(name) ? 'confirm' : 'read' }]);
    let otherService;
    if (name === 'cleanup') {
      // 合法 SQL 值、错误目录指纹：不绕过数据库约束，也不放宽正式目录校验。
      await db.prepare('UPDATE agent_mcp_services SET catalog_fingerprint=? WHERE id=?').bind('0'.repeat(64), service.id).run();
      otherService = await store.register('https://other.example.com/mcp');
    }
    cases.set(name, { name, ownerId, deviceId: issued.deviceId, token: issued.token, service,
      otherService,
      prompt: `合成联合测试 ${name} ${crypto.randomUUID()}`, models: [], counts: {}, dropped: 0,
      delayMillis: ['read_slow', 'approve_slow'].includes(name) ? 600 : 0 });
  }
  server = createServer({ pfx: await readFile(pfx), passphrase: 'synthetic-fixture' }, async (request, response) => {
    try {
      const current = [...cases.values()].find(row => request.headers.authorization === `Bearer ${row.token}`);
      assert.ok(current, '只接受本次虚构设备');
      if (request.url === '/__fixture/stats' && request.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(current.counts)); return;
      }
      assert.match(request.url, /^\/v1\/agent\//);
      const chunks = []; let length = 0;
      for await (const chunk of request) { length += chunk.length; assert.ok(length <= 1048576); chunks.push(chunk); }
      const body = Buffer.concat(chunks).toString('utf8');
      assert.ok(!body.includes('synthetic-plugin-'), '插件密钥不能进入云端请求');
      const action = current.name === 'cleanup' ? (request.method === 'POST' && request.url.endsWith('/remove') ? 'remove'
        : request.method === 'GET' && request.url.endsWith('/summary') ? 'summary'
        : request.method === 'GET' && request.url === '/v1/agent/mcp/services' ? 'list' : 'detail')
        : request.url.endsWith('/mcp/reply') ? 'reply' : request.url.endsWith('/mcp/take') ? 'take'
        : request.url.endsWith('/mcp/decision') ? 'decision' : request.url.endsWith('/mcp/close') ? 'close'
        : request.method === 'POST' && request.url === '/v1/agent/tasks' ? 'submit' : request.method === 'POST' ? 'control' : 'get';
      current.counts[action] = (current.counts[action] ?? 0) + 1;
      const route = action !== 'get' ? action : request.url.endsWith('/mcp') ? 'confirmation'
        : request.url.startsWith('/v1/agent/mcp/services/') ? 'service' : 'task';
      const started = performance.now();
      if (current.delayMillis) await new Promise(resolve => setTimeout(resolve, current.delayMillis));
      const forwarded = await bounded(runtime.dispatchFetch(`https://local.invalid${request.url}`, { method: request.method,
        headers: { Authorization: request.headers.authorization, 'Content-Type': 'application/json' },
        ...(request.method === 'GET' ? {} : { body }) }), '本地 Worker 请求', 15000);
      const text = await forwarded.text();
      requests.push({ case: current.name, action, route, status: forwarded.status,
        startedMillis: Math.round(started - startedAt),
        elapsedMillis: Math.round(performance.now() - started), injectedMillis: current.delayMillis });
      // SDK 的工具结果已经回云，但原生收不到 ACK；关闭后不得补发或把结果当作确认成功。
      if (current.name === 'lose_reply' && action === 'reply' && JSON.parse(body).text?.includes('手机合成工具结果')) {
        assert.equal(forwarded.status, 200); assert.equal(current.dropped++, 0);
        request.socket.destroy(); return;
      }
      if (current.name === 'cleanup' && action === 'remove') {
        assert.equal(forwarded.status, 200); assert.equal(current.dropped++, 0);
        request.socket.destroy(); return;
      }
      response.writeHead(forwarded.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(text);
    } catch (error) {
      // 保留失败但不打印请求头、令牌或正文。
      requests.push({ failure: error.message });
      response.destroy();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  port = server.address().port;
  await shell('reverse', `tcp:${port}`, `tcp:${port}`); reversed = true;
  const fixture = Buffer.from(JSON.stringify({ fixtureOnly: true, origin: `https://127.0.0.1:${port}`,
    certificate: (await readFile(cert)).toString('base64'), definition, argumentsJson,
    cases: [...cases.values()].map(({ name, ownerId, deviceId, token, service, otherService, prompt }) =>
      ({ name, ownerId, deviceId, token, serviceId: service.id, revision: service.revision, otherServiceId: otherService?.id, prompt })) })).toString('base64');
  phase = 'Android 联合执行';
  console.log(`隔离本地联合测试开始：${names.length} 个合成场景，不访问外部服务。`);
  const result = await execute(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'fixture', fixture,
    '-e', 'class', 'com.healthyagent.running.agent.mcp.McpWorkerJointTest',
    'com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner'], { timeout: 180000, maxBuffer: 1048576 });
  await writeFile(join(directory, 'instrumentation.log'), result.stdout + result.stderr);
  console.log(result.stdout);
  assert.ok(result.stdout.includes(`OK (${names.length} tests)`), '必须真正执行并通过全部测试');
  assert.ok(!result.stdout.includes('FAILURES'));
  assert.equal([...result.stdout.matchAll(/^INSTRUMENTATION_STATUS_CODE: 0\r?$/gm)].length, names.length, '不把跳过或假设不满足计为通过');
  phase = '独立核对 D1、派发次数和模型观察';
  for (const current of cases.values()) {
    const rows = (await db.prepare('SELECT id,status FROM agent_cloud_tasks WHERE owner_id=?').bind(current.ownerId).all()).results;
    if (current.name === 'cleanup') {
      assert.equal(rows.length, 0); assert.equal(current.models.length, 0);
      assert.deepEqual(current.counts, { list: 3, detail: 1, summary: 3, remove: 1 });
      assert.equal(current.dropped, 1);
      const remaining = (await db.prepare('SELECT id,revision,endpoint,enabled FROM agent_mcp_services WHERE owner_id=?')
        .bind(current.ownerId).all()).results;
      assert.deepEqual(remaining, [{ id: current.otherService.id, revision: current.otherService.revision,
        endpoint: current.otherService.endpoint, enabled: 0 }]);
      const samples = requests.filter(row => row.case === current.name);
      assert.equal(samples.find(row => row.action === 'remove').status, 200);
      assert.ok(samples.filter(row => row.action !== 'detail').every(row => row.status === 200));
      assert.equal(samples.find(row => row.action === 'detail').status, 503);
      console.log('cleanup：坏目录已移除，旧版本和旧私密代次被拒绝；回执丢失未重发，其他服务保留。');
      continue;
    }
    assert.equal(rows.length, 1);
    assert.equal(rows[0].status, current.name === 'cancel' ? 'cancelled' : current.name === 'expire' ? 'timed_out' : 'completed');
    const invocation = await db.prepare('SELECT status FROM agent_mcp_invocations WHERE task_id=?').bind(rows[0].id).first();
    const expected = { read: 'completed', approve: 'completed', deny: 'denied', lose_reply: 'may_have_dispatched', cancel: 'pending',
      background: 'approved', revoke_private: 'approved', disable_cloud: 'approved', expire: 'pending',
      read_slow: 'completed', approve_slow: 'completed' }[current.name];
    assert.equal(invocation.status, expected);
    assert.equal(current.counts.submit, 1);
    assert.equal(current.models.length, ['cancel', 'expire'].includes(current.name) ? 1 : 2);
    assert.equal(current.dropped, current.name === 'lose_reply' ? 1 : 0);
    const samples = requests.filter(row => row.case === current.name);
    current.metrics = { requests: samples.length, routeCounts: Object.fromEntries([...new Set(samples.map(row => row.route))]
      .map(route => [route, samples.filter(row => row.route === route).length])),
      summedRequestMillis: samples.reduce((sum, row) => sum + row.elapsedMillis, 0),
      injectedMillis: samples.reduce((sum, row) => sum + row.injectedMillis, 0) };
    const submitted = samples.find(row => row.action === 'submit');
    const acknowledged = samples.find(row => row.action === 'control');
    if (acknowledged) current.metrics.submitToFirstControlMillis = acknowledged.startedMillis + acknowledged.elapsedMillis - submitted.startedMillis;
    console.log(`${current.name}：任务 ${rows[0].status}，插件 ${expected}，${samples.length} 次云请求，恢复无重放。`);
  }
  assert.ok(!requests.some(row => row.failure), '本地转发不能有隐藏失败');
  await writeFile(join(directory, 'report.json'), JSON.stringify({ passed: true,
    cases: [...cases.values()].map(({ name, counts, models, dropped, metrics, observation }) =>
      ({ name, counts, modelCalls: models.length, dropped, observation, metrics })), requests }, null, 2));
  console.log(`合成证据保留在 ${directory}`);
} catch (error) {
  // 初始化失败也保留原始错误，诊断读取失败不能替代业务失败。
  const state = db ? await bounded(db.prepare('SELECT status,reason FROM agent_cloud_tasks').all(), '失败状态快照', 3000)
    .then(result => result.results).catch(() => null) : null;
  await writeFile(join(directory, 'failure.json'), JSON.stringify({ phase, error: error.message, requests, state,
    cases: [...cases.values()].map(({ name, counts, models, dropped }) => ({ name, counts, modelCalls: models.length, dropped })) }, null, 2));
  console.error(`联合测试未通过：${phase}；证据 ${directory}`);
  throw error;
} finally {
  if (reversed) await shell('reverse', '--remove', `tcp:${port}`).catch(() => {});
  server?.closeAllConnections();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  if (runtime) await bounded(runtime.dispose(), '本地运行时收尾');
}
