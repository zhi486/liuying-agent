// 隔离模拟器专用：真实 TLS 分块流、Room 与 Worker/DO；全部模型输出为固定合成数据。
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:https';
import { once } from 'node:events';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { readD1Migrations } from '@cloudflare/vitest-plugin';
import { createDeviceAdmin } from '../src/device-auth.ts';

const execute = promisify(execFile);
const serial = process.argv[2];
const webview = process.argv[3] === '--webview';
assert.ok(process.argv[3] === undefined || webview, '未知测试模式');
const names = webview ? ['tools', 'background'] : ['tools', 'disconnect', 'withdraw', 'revoke', 'truncated'];
assert.match(serial ?? '', /^emulator-\d+$/, '只接受明确的隔离模拟器，不允许真实手机');
const androidSdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
assert.ok(androidSdk, '请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT');
const adb = join(androidSdk, 'platform-tools/adb.exe');
const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/keytool.exe') : 'keytool.exe';
const shell = async (...args) => (await execute(adb, ['-s', serial, ...args], { timeout: 15000 })).stdout.trim();
assert.equal(await shell('shell', 'getprop', 'ro.kernel.qemu'), '1');
assert.equal(await shell('shell', 'settings', 'get', 'global', 'airplane_mode_on'), '1');
assert.equal(await shell('shell', 'settings', 'get', 'global', 'wifi_on'), '0');
assert.equal(await shell('shell', 'ip', 'route'), '', '必须先断开模拟器外部网络');
const directory = await mkdtemp(fileURLToPath(new URL('../.generated/native-stream-', import.meta.url)));
const bundle = fileURLToPath(new URL('../.generated/bundle/', import.meta.url));
const cases = new Map();
const requests = [];
let runtime, db, server, port, active, reversed = false;
const event = (delta, finish = null) => `data: ${JSON.stringify({ id: 'fixture_stream', object: 'chat.completion.chunk',
  choices: [{ index: 0, delta, finish_reason: finish }],
  usage: finish ? { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } : null })}\n\n`;
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
async function bounded(work, label, timeout = 30000) {
  let timer;
  try { return await Promise.race([work, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label}超时`)), timeout);
  })]); } finally { clearTimeout(timer); }
}
function stats(current) {
  return { submits: current.submits, models: current.models, memoryReplies: current.memoryReplies,
    cursors: current.cursors, failedGets: current.failedGets, closedStreams: current.closedStreams };
}
try {
  const pfx = join(directory, 'fixture.p12'); const cert = join(directory, 'fixture.cer');
  await execute(keytool, ['-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
    '-dname', 'CN=127.0.0.1', '-ext', 'SAN=ip:127.0.0.1', '-ext', 'BC=ca:true', '-storetype', 'PKCS12',
    '-keystore', pfx, '-storepass', 'synthetic-fixture', '-keypass', 'synthetic-fixture'], { timeout: 15000 });
  await execute(keytool, ['-exportcert', '-alias', 'fixture', '-keystore', pfx, '-storepass', 'synthetic-fixture', '-file', cert], { timeout: 15000 });
  const checked = Date.now();
  runtime = new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0, cf: false, resourcePersistencePath: join(directory, 'state'),
    telemetry: { enabled: false }, unsafeRegisterWorker: false,
    name: 'native-stream-joint-test', modulesRoot: bundle,
    modules: [{ type: 'ESModule', path: join(bundle, 'worker.js') },
      ...(await readdir(bundle)).filter(name => name.endsWith('.bin')).map(name => ({ type: 'Data', path: join(bundle, name) }))],
    compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
    durableObjects: { AGENT_RUNTIME: { className: 'LiuyingAgentRuntime', useSQLite: true } }, d1Databases: ['DB'],
    bindings: { DEEPSEEK_API_KEY: 'fixture-only-not-a-secret', RATE_CARD_JSON: JSON.stringify({
      version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
      checkedAtMillis: checked, expiresAtMillis: checked + 3600000,
    }) },
    outboundService: async request => {
      assert.equal(request.url, 'https://api.deepseek.com/chat/completions', '禁止未模拟的外部访问');
      assert.equal(request.method, 'POST');
      const body = await request.json(); const current = active;
      assert.ok(current, '必须先有合成提交'); assert.equal(body.stream, true);
      assert.equal(body.messages.filter(row => row.role === 'user').at(-1).content, '我喜欢夜跑');
      const round = ++current.models;
      assert.ok(round <= (['tools', 'revoke'].includes(current.name) ? 2 : 1), '禁止隐式重试模型');
      if (round === 2) {
        const observation = JSON.parse(body.messages.at(-1).content);
        assert.equal(observation.status, current.name === 'tools' ? 'saved' : 'rejected');
        if (current.name === 'revoke') assert.equal(observation.reason, 'consent_required');
        current.observation = observation.status;
        await current.answer.promise;
      }
      const first = round === 1 ? '合成首段🙂' : '合成最终首段';
      // 返回前先交付首段，后续必须由原生观察后显式释放，不能靠 EOF 模拟流式。
      const stream = new ReadableStream({ start(controller) {
        const send = text => controller.enqueue(new TextEncoder().encode(text));
        send(event({ role: 'assistant', content: first }));
        const work = (async () => {
          if (round === 1 && ['tools', 'revoke'].includes(current.name)) {
            await current.tool.promise;
            send(event({ tool_calls: [{ index: 0, id: 'save_1', type: 'function', function: {
              name: 'memory_upsert', arguments: JSON.stringify({ category: 'preference', evidence: '我喜欢夜跑', target: null }),
            } }] }) + event({}, 'tool_calls') + 'data: [DONE]\n\n');
          } else {
            await current.finish.promise;
            if (current.name !== 'truncated') send(event({ content: '，完整尾句。' }) + event({}, 'stop') + 'data: [DONE]\n\n');
          }
          controller.close();
        })().catch(error => { current.providerErrors.push(error.message); });
        current.providers.push(work);
      } });
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } });
    },
  }));
  await bounded(runtime.ready, 'workerd 启动'); db = await runtime.getD1Database('DB');
  for (const migration of await readD1Migrations(fileURLToPath(new URL('../migrations', import.meta.url))))
    await db.batch(migration.queries.map(sql => db.prepare(sql)));
  for (const name of names) {
    const ownerId = crypto.randomUUID();
    await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
    const issued = await createDeviceAdmin({ db }).issue(ownerId);
    cases.set(name, { name, ownerId, deviceId: issued.deviceId, token: issued.token, tool: deferred(), answer: deferred(), finish: deferred(),
      submits: 0, models: 0, memoryReplies: 0, cursors: [], failedGets: 0, closedStreams: 0,
      failReads: false, streams: new Set(), providers: [], providerErrors: [] });
  }
  server = createServer({ pfx: await readFile(pfx), passphrase: 'synthetic-fixture' }, async (request, response) => {
    let reader;
    let closed = false;
    try {
      const current = [...cases.values()].find(row => request.headers.authorization === `Bearer ${row.token}`);
      assert.ok(current, '仅接受本次虚构设备');
      if (request.method === 'GET' && request.url.startsWith('/__fixture/')) {
        const action = request.url.slice('/__fixture/'.length);
        assert.ok(['stats', 'tool', 'answer', 'finish', 'cut', 'restore'].includes(action));
        if (action === 'tool') current.tool.resolve();
        if (action === 'answer') current.answer.resolve();
        if (action === 'finish') {
          if (current.name === 'withdraw') {
            const row = await db.prepare('SELECT status,result_state FROM agent_cloud_tasks WHERE owner_id=?').bind(current.ownerId).first();
            assert.deepEqual(row, { status: 'cancelled', result_state: 'withdrawn' });
          }
          current.finish.resolve();
        }
        if (action === 'cut') {
          assert.ok(['disconnect', 'background'].includes(current.name));
          assert.equal(current.streams.size, current.name === 'background' ? 0 : 1);
          current.failReads = true; for (const stream of current.streams) stream.destroy();
        }
        if (action === 'restore') current.failReads = false;
        response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(stats(current))); return;
      }
      assert.match(request.url, /^\/v1\/agent\//);
      const chunks = []; let length = 0;
      for await (const chunk of request) { length += chunk.length; assert.ok(length <= 1048576); chunks.push(chunk); }
      const body = Buffer.concat(chunks).toString('utf8');
      const streaming = /\/events\/(\d+)$/.exec(request.url);
      const taskRead = request.method === 'GET' && /\/tasks\/[^/]+$/.test(request.url);
      if (taskRead && current.failReads) {
        current.failedGets++; response.writeHead(503); response.end(); return;
      }
      if (request.method === 'POST' && request.url === '/v1/agent/tasks') {
        current.submits++; assert.equal(current.submits, 1);
        assert.equal(request.headers['x-agent-stream'], 'task_events_v1');
        assert.deepEqual(JSON.parse(body).summaries, []); active = current;
      }
      if (request.method === 'POST' && request.url.endsWith('/memory')) current.memoryReplies++;
      if (streaming) current.cursors.push(Number(streaming[1]));
      const forwarded = await bounded(runtime.dispatchFetch(`https://local.invalid${request.url}`, {
        method: request.method, headers: { Authorization: request.headers.authorization, 'Content-Type': 'application/json',
          ...(request.headers['x-agent-stream'] ? { 'X-Agent-Stream': request.headers['x-agent-stream'] } : {}) },
        ...(request.method === 'GET' ? {} : { body }),
      }), 'Worker 请求', 15000);
      requests.push({ case: current.name, method: request.method, stream: Boolean(streaming), status: forwarded.status });
      response.writeHead(forwarded.status, { 'Content-Type': forwarded.headers.get('Content-Type') ?? 'application/json', 'Cache-Control': 'no-store' });
      if (!streaming) { response.end(await forwarded.text()); return; }
      assert.equal(forwarded.status, 200); response.flushHeaders(); current.streams.add(response);
      reader = forwarded.body.getReader();
      response.on('close', () => {
        closed = true; current.streams.delete(response); current.closedStreams++;
        void reader.cancel().catch(() => {});
      });
      while (!closed) {
        const part = await reader.read(); if (part.done || closed) break;
        if (!response.write(part.value)) await Promise.race([once(response, 'drain'), once(response, 'close')]);
      }
      if (!closed) response.end();
    } catch (error) {
      if (!closed) { requests.push({ failure: error.message }); response.destroy(); }
    } finally { if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock(); } }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); port = server.address().port;
  await shell('reverse', `tcp:${port}`, `tcp:${port}`); reversed = true;
  const fixture = Buffer.from(JSON.stringify({ fixtureOnly: true, origin: `https://127.0.0.1:${port}`,
    certificate: (await readFile(cert)).toString('base64'),
    cases: [...cases.values()].map(({ name, ownerId, deviceId, token }) => ({ name, ownerId, deviceId, token })) })).toString('base64');
  console.log(`开始 ${names.length} 项隔离 HTTPS ${webview ? 'Bridge／WebView' : '原生'}流式联合测试；不会访问真实模型或用户手机。`);
  const result = await execute(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'fixture', fixture,
    '-e', 'class', `com.healthyagent.running.agent.${webview ? 'StreamWebViewJointTest' : 'StreamWorkerJointTest'}`,
    'com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner'], { timeout: 180000, maxBuffer: 1048576 });
  await writeFile(join(directory, 'instrumentation.log'), result.stdout + result.stderr); console.log(result.stdout);
  assert.ok(result.stdout.includes(`OK (${names.length} tests)`));
  assert.equal([...result.stdout.matchAll(/^INSTRUMENTATION_STATUS_CODE: 0\r?$/gm)].length, names.length, '跳过不算通过');
  for (const current of cases.values()) {
    const rows = (await db.prepare('SELECT status,reason FROM agent_cloud_tasks WHERE owner_id=?').bind(current.ownerId).all()).results;
    assert.equal(rows.length, 1); assert.equal(current.submits, 1);
    assert.equal(rows[0].status, current.name === 'withdraw' ? 'cancelled' : current.name === 'truncated' ? 'failed' : 'completed');
    assert.equal(current.models, ['tools', 'revoke'].includes(current.name) ? 2 : 1);
    assert.equal(current.memoryReplies, ['tools', 'revoke'].includes(current.name) ? 1 : 0);
    assert.deepEqual(current.cursors, current.name === 'disconnect' ? [0, 2] : current.name === 'background' ? [0, 0] : [0]);
    assert.equal(current.providerErrors.length, 0);
  }
  assert.ok(!requests.some(row => row.failure));
  await writeFile(join(directory, 'report.json'), JSON.stringify({ passed: true,
    cases: [...cases.values()].map(current => ({ name: current.name, ...stats(current), observation: current.observation })), requests }, null, 2));
  console.log(`整链路合成证据：${directory}`);
} catch (error) {
  await writeFile(join(directory, 'failure.json'), JSON.stringify({ error: error.message, requests,
    cases: [...cases.values()].map(current => ({ name: current.name, ...stats(current), providerErrors: current.providerErrors })) }, null, 2));
  console.error(`联合测试失败，证据保留：${directory}`); throw error;
} finally {
  for (const current of cases.values()) { current.tool.resolve(); current.answer.resolve(); current.finish.resolve(); }
  await Promise.all([...cases.values()].flatMap(current => current.providers));
  if (reversed) await shell('reverse', '--remove', `tcp:${port}`).catch(() => {});
  server?.closeAllConnections();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  if (runtime) await bounded(runtime.dispose(), '运行时收尾');
}
