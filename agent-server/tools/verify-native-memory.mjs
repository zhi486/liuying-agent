// 隔离模拟器专用：真实 HTTPS、Room、Worker/DO；模型端为确定性合成替身。
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

const execute = promisify(execFile);
const serial = process.argv[2];
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
assert.equal(await shell('shell', 'ip', '-6', 'route'), '', 'IPv6 也不能存在外部路由');
const directory = await mkdtemp(fileURLToPath(new URL('../.generated/native-memory-', import.meta.url)));
const bundle = fileURLToPath(new URL('../.generated/bundle/', import.meta.url));
const cases = new Map();
const definitions = [
  { name: 'lifecycle', prompt: '我喜欢夜跑', evidence: '我喜欢夜跑' },
  { name: 'natural_correction', prompt: '我喜欢把测试计划排在周三', evidence: '我喜欢把测试计划排在周三' },
  { name: 'revoke', prompt: '我喜欢夜跑', evidence: '我喜欢夜跑' },
  { name: 'search_revoke', prompt: '我的专业是什么？请只检索已保存的专业，不保存或修改任何记忆。', evidence: '我的专业是流萤记忆验收工程' },
  { name: 'search_reenable', prompt: '我的专业是什么？请只检索已保存的专业，不保存或修改任何记忆。', evidence: '我的专业是流萤记忆验收工程' },
  { name: 'lose_reply', prompt: '我喜欢夜跑', evidence: '我喜欢夜跑' },
  { name: 'natural', prompt: '今晚先聊训练。我更喜欢晚上跑步，你能帮我安排吗？', evidence: '我更喜欢晚上跑步' },
  { name: 'negative', prompt: '对了，我不喜欢太长的回答。之后聊训练就简短些。', evidence: '我不喜欢太长的回答' },
  { name: 'decline', prompt: '我喜欢夜跑，别帮我保存下来', evidence: '我喜欢夜跑' },
  { name: 'manual_delete', prompt: '我喜欢夜跑', evidence: '我喜欢夜跑' },
  { name: 'fact_lifecycle', category: 'fact', factsAllowed: true, prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
  { name: 'fact_revoke', category: 'fact', factsAllowed: true, prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
  { name: 'fact_legacy', category: 'fact', prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
  { name: 'fact_readonly', category: 'fact', factsAllowed: true, scopes: ['facts', 'basic', 'basic', 'off'],
    prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
  { name: 'fact_storage', category: 'fact', factsAllowed: true, prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
  { name: 'fact_lose_reply', category: 'fact', factsAllowed: true, prompt: '我的主要编程语言是Kotlin', evidence: '我的主要编程语言是Kotlin' },
];
const requests = [];
let runtime, db, server, port, active, reversed = false;
let phase = '本地初始化';
function expectedScope(current, index) {
  if (!current.scopes) return current.factsAllowed ? 'facts' : 'basic';
  assert.ok(index < current.scopes.length, '不能凭未定义的新回合扩大夹具范围');
  return current.scopes[index];
}
async function bounded(work, label, timeout = 30000) {
  let timer;
  try { return await Promise.race([work, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label}超时`)), timeout);
  })]); } finally { clearTimeout(timer); }
}
function modelReply(call) {
  return Response.json({ choices: [{ index: 0, finish_reason: call ? 'tool_calls' : 'stop', message: {
    role: 'assistant', content: call ? null : '合成记忆回答',
    ...(call ? { tool_calls: [{ id: call.id, type: 'function', function: { name: call.name, arguments: JSON.stringify(call.args) } }] } : {}),
  } }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } });
}
try {
  const pfx = join(directory, 'fixture.p12');
  const cert = join(directory, 'fixture.cer');
  await execute(keytool, ['-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
    '-dname', 'CN=127.0.0.1', '-ext', 'SAN=ip:127.0.0.1', '-ext', 'BC=ca:true', '-storetype', 'PKCS12',
    '-keystore', pfx, '-storepass', 'synthetic-fixture', '-keypass', 'synthetic-fixture'], { timeout: 15000 });
  await execute(keytool, ['-exportcert', '-alias', 'fixture', '-keystore', pfx, '-storepass', 'synthetic-fixture', '-file', cert], { timeout: 15000 });
  const checked = Date.now();
  runtime = new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0, cf: false, resourcePersistencePath: join(directory, 'state'),
    telemetry: { enabled: false }, unsafeRegisterWorker: false,
    name: 'native-memory-joint-test', modulesRoot: bundle,
    modules: [{ type: 'ESModule', path: join(bundle, 'worker.js') },
      ...(await readdir(bundle)).filter(name => name.endsWith('.bin')).map(name => ({ type: 'Data', path: join(bundle, name) }))],
    compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
    durableObjects: { AGENT_RUNTIME: { className: 'LiuyingAgentRuntime', useSQLite: true } }, d1Databases: ['DB'],
    bindings: { DEEPSEEK_API_KEY: 'fixture-only-not-a-secret', RATE_CARD_JSON: JSON.stringify({
      version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
      checkedAtMillis: checked, expiresAtMillis: checked + 3600000,
    }) },
    outboundService: async request => {
      assert.equal(request.url, 'https://api.deepseek.com/chat/completions', '禁止真实外部访问');
      assert.equal(request.method, 'POST');
      const body = await request.json();
      const current = active;
      assert.ok(current, '必须先由正式提交建立当前合成任务');
      const turn = current.turns.at(-1);
      assert.equal(body.messages.filter(row => row.role === 'user').at(-1).content, turn.prompt);
      const scope = expectedScope(current, current.turns.length - 1);
      const definition = body.tools?.find(tool => tool.function.name === 'memory_upsert');
      if (scope === 'off') {
        assert.equal(definition, undefined);
        assert.ok(!body.tools?.some(tool => tool.function.name.startsWith('memory_')));
      } else assert.deepEqual(definition.function.parameters.properties.category.enum,
        scope === 'facts' ? ['preference', 'goal', 'fact'] : ['preference', 'goal']);
      assert.ok(!JSON.stringify(body).includes('合成事实回执失败'), 'SQLite 错误正文不能泄漏给模型');
      turn.models++;
      const step = turn.models;
      const fact = current.category === 'fact';
      const naturalCorrection = current.name === 'natural_correction';
      const corrected = naturalCorrection ? '我喜欢把测试计划排在周四' : fact ? '我的主要编程语言是TypeScript' : '我喜欢晨跑';
      const correctionPrompt = naturalCorrection ? `${corrected}。请更正之前的周三偏好，不要新增重复记录。` : corrected;
      const recallPrompt = naturalCorrection ? '我希望把测试计划安排在哪一天？' : fact ? '我主要用什么编程语言' : '我喜欢什么跑步时间';
      const deletedPrompt = naturalCorrection ? '现在还记得我的测试计划偏好吗' : fact ? '现在还记得我的主要编程语言吗' : '现在还有晨跑偏好吗';
      const save = (evidence, target = null) => modelReply({ id: `save_${step}`, name: 'memory_upsert',
        args: { category: current.category ?? 'preference', evidence, target } });
      const search = query => modelReply({ id: `search_${step}`, name: 'memory_search', args: { query } });
      if (['search_revoke', 'search_reenable'].includes(current.name)) {
        const index = current.turns.length - 1;
        assert.equal(turn.prompt, index === 0 ? current.prompt : '我的主要编程语言是Kotlin。我的专业是什么？');
        assert.ok(index === 0 || (index === 1 && current.name === 'search_reenable'));
        if (step === 1) return search('专业');
        assert.equal(step, 2);
        const observation = JSON.parse(body.messages.at(-1).content);
        if (index === 0) assert.deepEqual(observation, { status: 'rejected', reason: 'consent_required' });
        else {
          assert.equal(observation.status, 'found'); assert.equal(observation.items.length, 1);
          const memory = observation.items[0];
          assert.deepEqual(Object.keys(memory).sort(), ['content', 'id', 'version']);
          assert.equal(memory.content, current.evidence); assert.equal(memory.version, 1);
          assert.match(memory.id, /^[0-9a-f-]{36}$/);
        }
        turn.observations.push(observation.status); return modelReply();
      }
      if (current.name === 'fact_storage') {
        if (step === 1) return save(current.evidence);
        const observation = JSON.parse(body.messages.at(-1).content);
        assert.deepEqual(observation, { status: 'unconfirmed', reason: 'unavailable' });
        turn.observations.push(observation.status);
        if (step === 2) return save(current.evidence, { id: crypto.randomUUID(), version: 1 });
        assert.equal(step, 3); return modelReply();
      }
      if (current.name === 'fact_readonly') {
        const index = current.turns.length - 1;
        if (step === 1) {
          assert.equal(turn.prompt, [current.prompt, recallPrompt, corrected, deletedPrompt][index]);
          // 后两轮故意让合成模型越权，验证实际目录边界而非依赖模型自觉。
          return index === 0 || index === 2 ? save(index === 0 ? current.evidence : corrected) : search('编程语言');
        }
        assert.equal(step, 2);
        const observation = JSON.parse(body.messages.at(-1).content);
        if (index === 0) {
          assert.equal(observation.status, 'saved'); assert.equal(observation.replayed, false);
          assert.equal(observation.memory.content, current.evidence); assert.equal(observation.memory.version, 1);
          current.memory = observation.memory;
        } else {
          assert.equal(index, 1); assert.deepEqual(observation, { status: 'found', items: [current.memory] });
        }
        turn.observations.push(observation.status); return modelReply();
      }
      if (current.name === 'manual_delete') {
        if (step === 1) {
          if (turn.prompt === '现在还有晨跑偏好吗') return search('晨跑');
          assert.ok([current.prompt, '我喜欢晨跑', '请记住：我喜欢晨跑'].includes(turn.prompt));
          return save(turn.prompt === current.prompt ? current.evidence : '我喜欢晨跑');
        }
        assert.equal(step, 2);
        const observation = JSON.parse(body.messages.at(-1).content);
        turn.observations.push(observation.status);
        if (turn.prompt === current.prompt || turn.prompt === '我喜欢晨跑') {
          assert.deepEqual(observation, { status: 'rejected', reason: 'conflict' });
        } else if (turn.prompt === '请记住：我喜欢晨跑') {
          assert.equal(observation.status, 'saved'); assert.equal(observation.replayed, false);
          assert.equal(observation.memory.content, '我喜欢晨跑'); assert.equal(observation.memory.version, 1);
          current.memory = observation.memory;
        } else {
          assert.equal(observation.status, 'found'); assert.deepEqual(observation.items, [current.memory]);
        }
        return modelReply();
      }
      if (step === 1) {
        if (turn.prompt === current.prompt) return save(current.evidence);
        if (turn.prompt === '我的偏好是什么') return search(current.name === 'natural' ? '晚上跑步' : '回答');
        if (turn.prompt === recallPrompt || turn.prompt === correctionPrompt) return search(naturalCorrection ? '测试计划' : fact ? '编程语言' : current.memory.content);
        assert.equal(turn.prompt, deletedPrompt); return search(naturalCorrection ? '测试计划' : fact ? '编程语言' : '晨跑');
      }
      const observation = JSON.parse(body.messages.at(-1).content);
      turn.observations.push(observation.status);
      if (['revoke', 'fact_revoke', 'decline'].includes(current.name)) {
        assert.equal(step, 2);
        assert.deepEqual(observation, { status: 'rejected', reason: current.name === 'decline' ? 'evidence_required' : 'consent_required' });
      } else if (turn.prompt === current.prompt || step === 3) {
        assert.equal(observation.status, 'saved');
        assert.equal(observation.replayed, false);
        assert.equal(observation.memory.content, step === 3 ? corrected : current.evidence);
        assert.equal(observation.memory.version, step === 3 ? 2 : 1);
        if (step === 3) assert.equal(observation.memory.id, current.memory.id);
        current.memory = observation.memory;
      } else {
        assert.equal(step, 2); assert.equal(observation.status, 'found');
        assert.deepEqual(observation.items, turn.prompt === deletedPrompt ? [] : [current.memory]);
        if (turn.prompt === correctionPrompt) {
          const { id, version } = observation.items[0];
          return save(corrected, { id, version });
        }
      }
      assert.ok(step <= (turn.prompt === correctionPrompt ? 3 : 2));
      return modelReply();
    },
  }));
  await bounded(runtime.ready, 'workerd 启动');
  db = await runtime.getD1Database('DB');
  for (const migration of await readD1Migrations(fileURLToPath(new URL('../migrations', import.meta.url))))
    await db.batch(migration.queries.map(sql => db.prepare(sql)));
  for (const definition of definitions) {
    const ownerId = crypto.randomUUID();
    await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1)').bind(ownerId).run();
    const issued = await createDeviceAdmin({ db }).issue(ownerId);
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    cases.set(definition.name, { ...definition, ownerId, deviceId: issued.deviceId, token: issued.token,
      turns: [], counts: {}, dropped: 0, held: false, gate, release });
  }
  server = createServer({ pfx: await readFile(pfx), passphrase: 'synthetic-fixture' }, async (request, response) => {
    try {
      const current = [...cases.values()].find(row => request.headers.authorization === `Bearer ${row.token}`);
      assert.ok(current, '仅接受本次虚构设备');
      if (request.method === 'GET' && ['/__fixture/stats', '/__fixture/release'].includes(request.url)) {
        if (request.url === '/__fixture/release') current.release();
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ ...current.counts, held: current.held })); return;
      }
      assert.match(request.url, /^\/v1\/agent\//);
      const chunks = []; let length = 0;
      for await (const chunk of request) { length += chunk.length; assert.ok(length <= 1048576); chunks.push(chunk); }
      const body = Buffer.concat(chunks).toString('utf8');
      const action = request.method === 'GET' ? 'get' : request.url.endsWith('/memory') ? 'memory_reply'
        : request.url === '/v1/agent/tasks' ? 'submit' : 'control';
      current.counts[action] = (current.counts[action] ?? 0) + 1;
      if (current.name === 'fact_storage' && action === 'memory_reply')
        assert.deepEqual(JSON.parse(body).observation, { status: 'unconfirmed', reason: 'unavailable' });
      if (action === 'submit') {
        const submitted = JSON.parse(body);
        assert.deepEqual(submitted.conversation.messages, [], '跨会话必须通过真实检索而不是历史注入');
        assert.deepEqual(submitted.summaries, []);
        assert.equal(submitted.memoryContext, undefined, '不能用手选记忆正文替代自主检索');
        const scope = expectedScope(current, current.turns.length);
        if (scope === 'off') assert.equal(submitted.memoryTools, undefined);
        else {
          assert.match(submitted.memoryTools.generation, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
          assert.deepEqual(submitted.memoryTools, { generation: submitted.memoryTools.generation,
            version: scope === 'facts' ? 'native_memory_v2' : 'native_memory_v1',
            ...(scope === 'facts' ? { factsAllowed: true } : {}) });
        }
        if (['search_revoke', 'search_reenable'].includes(current.name)) {
          if (current.turns.length === 0) current.generation = submitted.memoryTools.generation;
          else assert.notEqual(submitted.memoryTools.generation, current.generation, '新请求必须绑定重授权代次');
        }
        current.turns.push({ prompt: submitted.prompt, models: 0, observations: [] }); active = current;
      }
      const forwarded = await bounded(runtime.dispatchFetch(`https://local.invalid${request.url}`, { method: request.method,
        headers: { Authorization: request.headers.authorization, 'Content-Type': 'application/json' },
        ...(request.method === 'GET' ? {} : { body }) }), '本地 Worker 请求', 15000);
      const text = await forwarded.text();
      requests.push({ case: current.name, action, status: forwarded.status });
      // 在本地 Worker 响应已取回、原生尚未处理时，先撤权或手工删除，再释放迟到调用。
      if (['revoke', 'fact_revoke', 'manual_delete', 'search_revoke', 'search_reenable'].includes(current.name) && request.method === 'GET' && request.url.endsWith('/memory') && !current.held) {
        const pending = JSON.parse(text).memory;
        assert.ok(pending); assert.equal(pending.version, 'native_memory_v1'); current.held = true;
        if (current.name.startsWith('search_')) {
          assert.equal(pending.call.name, 'memory.search');
          assert.deepEqual(pending.call.args, { query: '专业' });
          assert.equal(pending.generation, current.generation);
        }
        await bounded(current.gate, '等待原生撤权或删除', 5000);
      }
      // 云端已消费真实原生保存结果，但手机没有收到 ACK，禁止重新提交或再次保存。
      if (['lose_reply', 'fact_lose_reply'].includes(current.name) && action === 'memory_reply') {
        assert.equal(forwarded.status, 200); assert.equal(current.dropped++, 0);
        request.socket.destroy(); return;
      }
      response.writeHead(forwarded.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(text);
    } catch (error) {
      requests.push({ failure: error.message }); response.destroy();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); port = server.address().port;
  await shell('reverse', `tcp:${port}`, `tcp:${port}`); reversed = true;
  const fixture = Buffer.from(JSON.stringify({ fixtureOnly: true, origin: `https://127.0.0.1:${port}`,
    certificate: (await readFile(cert)).toString('base64'),
    cases: [...cases.values()].map(({ name, ownerId, deviceId, token, prompt, evidence }) => ({ name, ownerId, deviceId, token, prompt, evidence })) })).toString('base64');
  phase = '原生 HTTPS 联合执行';
  console.log(`隔离记忆联合测试开始：${cases.size} 个合成场景，不访问外部服务。`);
  const result = await execute(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'fixture', fixture,
    '-e', 'class', 'com.healthyagent.running.agent.MemoryWorkerJointTest',
    'com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner'], { timeout: 180000, maxBuffer: 1048576 });
  await writeFile(join(directory, 'instrumentation.log'), result.stdout + result.stderr);
  console.log(result.stdout);
  assert.ok(result.stdout.includes(`OK (${cases.size} tests)`));
  assert.equal([...result.stdout.matchAll(/^INSTRUMENTATION_STATUS_CODE: 0\r?$/gm)].length, cases.size, '跳过不算通过');
  phase = '独立核对任务、模型观察和恢复次数';
  for (const current of cases.values()) {
    const rows = (await db.prepare('SELECT status,reason FROM agent_cloud_tasks WHERE owner_id=?').bind(current.ownerId).all()).results;
    const lifecycle = ['lifecycle', 'fact_lifecycle', 'natural_correction'].includes(current.name);
    const legacy = current.name === 'fact_legacy';
    const readonly = current.name === 'fact_readonly';
    const expectedTasks = lifecycle ? 5 : current.name === 'manual_delete' || readonly ? 4 : ['natural', 'negative', 'search_reenable'].includes(current.name) ? 2 : 1;
    assert.equal(rows.length, expectedTasks);
    assert.equal(rows.filter(row => row.status === 'blocked' && row.reason === 'scope_denied').length, legacy ? 1 : readonly ? 2 : 0);
    assert.equal(rows.filter(row => row.status === 'completed' && row.reason === null).length, legacy ? 0 : readonly ? 2 : expectedTasks);
    assert.equal(current.counts.submit, expectedTasks);
    assert.equal(current.counts.control, expectedTasks, '每个最终答案只确认一次');
    assert.equal(current.counts.memory_reply ?? 0, legacy ? 0 : readonly ? 2 : lifecycle ? 6 : expectedTasks);
    assert.equal(current.dropped, ['lose_reply', 'fact_lose_reply'].includes(current.name) ? 1 : 0);
    assert.deepEqual(current.turns.map(turn => turn.models), legacy ? [1] : readonly ? [2, 2, 1, 1] :
      current.name === 'fact_storage' ? [3] : lifecycle ? [2, 2, 3, 2, 2] : Array(expectedTasks).fill(2));
    if (readonly) assert.deepEqual(current.turns.map(turn => turn.observations), [['saved'], ['found'], [], []]);
    if (current.name === 'fact_storage') assert.deepEqual(current.turns[0].observations, ['unconfirmed', 'unconfirmed']);
    if (lifecycle) assert.deepEqual(current.turns.map(turn => turn.observations), [['saved'], ['found'], ['found', 'saved'], ['found'], ['found']]);
    if (current.name === 'fact_revoke') {
      assert.equal(current.held, true); assert.deepEqual(current.turns[0].observations, ['rejected']);
    }
    if (current.name.startsWith('search_')) {
      assert.equal(current.held, true, '必须在 HTTPS 待执行搜索期间实际撤权');
      assert.deepEqual(current.turns.map(turn => turn.observations),
        current.name === 'search_reenable' ? [['rejected'], ['found']] : [['rejected']]);
    }
    if (current.name === 'manual_delete') {
      assert.equal(current.held, true, '必须实际阻挡一次待执行的工具响应');
      assert.deepEqual(current.turns.map(turn => turn.observations), [['rejected'], ['rejected'], ['saved'], ['found']]);
    }
    console.log(`${current.name}：${rows.length} 个终态任务，${current.counts.memory_reply ?? 0} 次原生工具回传，恢复无重放。`);
  }
  assert.ok(!requests.some(row => row.failure), '转发错误不能被业务成功掩盖');
  await writeFile(join(directory, 'report.json'), JSON.stringify({ passed: true,
    cases: [...cases.values()].map(({ name, counts, dropped, turns }) => ({ name, counts, dropped,
      turns: turns.map(({ models, observations }) => ({ modelCalls: models, observations })) })), requests }, null, 2));
  console.log(`合成证据保留在 ${directory}`);
} catch (error) {
  const state = db ? await bounded(db.prepare('SELECT status,reason FROM agent_cloud_tasks').all(), '失败快照', 3000)
    .then(result => result.results).catch(() => null) : null;
  await writeFile(join(directory, 'failure.json'), JSON.stringify({ phase, error: error.message, requests, state,
    cases: [...cases.values()].map(({ name, counts, dropped, turns }) => ({ name, counts, dropped, turns })) }, null, 2));
  console.error(`联合测试未通过：${phase}；证据 ${directory}`); throw error;
} finally {
  for (const current of cases.values()) current.release();
  if (reversed) await shell('reverse', '--remove', `tcp:${port}`).catch(() => {});
  server?.closeAllConnections();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  if (runtime) await bounded(runtime.dispose(), '本地运行时收尾');
}
