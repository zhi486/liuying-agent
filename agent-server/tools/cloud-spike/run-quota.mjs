import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { waitReady } from './readiness.mjs';

// 仅用于已授权的一次性云探针；密钥仅保存到已忽略的本地会话文件和官方 secret。
const base = 'https://liuying-free-spike-20260915.fangran31415927.workers.dev';
const mode = process.argv[2] ?? 'quota';
assert.ok(['quota', 'agent', 'prepare'].includes(mode), '仅允许固定探针模式');
const output = new URL('.output/', import.meta.url);
const tokenFile = new URL('session-token', output);
let token;
try { token = await readFile(tokenFile, 'utf8'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const cli = new URL('node_modules/wrangler/bin/wrangler.js', import.meta.url);
if (!token) {
  token = randomBytes(32).toString('hex');
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(cli), 'secret', 'put', 'SPIKE_TOKEN'], {
      cwd: fileURLToPath(new URL('.', import.meta.url)),
      env: { ...process.env, WRANGLER_SEND_METRICS: 'false' },
      stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true, timeout: 45000,
    });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`测试密钥设置失败：${code}`)));
    child.stdin.on('error', reject);
    child.stdin.end(`${token}\n`);
  });
  await mkdir(output, { recursive: true });
  await writeFile(tokenFile, token, { flag: 'wx', mode: 0o600 });
  console.log('临时测试密钥已设置并保存到忽略的会话文件，值不输出。');
} else {
  assert.match(token, /^[0-9a-f]{64}$/, '本地测试会话密钥格式无效');
  console.log('复用本轮测试密钥，不创建新部署，值不输出。');
}
if (mode === 'prepare') process.exit(0);
await waitReady(async () => {
  const response = await fetch(base + '/ready-not-a-route', {
    method: 'POST', headers: { authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(10000), redirect: 'error',
  });
  await response.arrayBuffer();
  console.log(`部署就绪检查：${response.status}（不访问数据库）`);
  return response.status;
});
const report = { startedAt: new Date().toISOString(), checks: [] };
async function check(name, path, authorization, method = 'POST') {
  const response = await fetch(base + path, {
    method, headers: authorization ? { authorization } : {},
    signal: AbortSignal.timeout(20000), redirect: 'error',
  });
  const text = await response.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = text.slice(0, 500); }
  const result = { name, status: response.status, body };
  report.checks.push(result);
  console.log(JSON.stringify(result));
  return result;
}
try {
  if (mode === 'agent') {
    for (const name of ['one', 'three', 'background']) {
      const result = await check(name, `/agent/${name}`, `Bearer ${token}`);
      assert.equal(result.status, name === 'background' ? 202 : 200);
      assert.equal(result.body.transport, 'fake-only');
      assert.ok(result.body.taskId);
    }
  } else {
    assert.equal((await check('缺少令牌', '/quota/seq-50')).status, 401);
    assert.equal((await check('错误令牌', '/quota/seq-50', 'Bearer wrong')).status, 401);
    const authorization = `Bearer ${token}`;
    assert.equal((await check('非白名单路径', '/quota/seq-999', authorization)).status, 404);
    const control = await check('seq-50', '/quota/seq-50', authorization);
    assert.equal(control.status, 200);
    assert.equal(control.body.outcome, 'completed', '50次控制组失败，停止后续配额判读');
    assert.equal(control.body.completedSql, 50);
    for (const name of ['seq-51', 'batch-51', 'mixed-49-2'])
      await check(name, `/quota/${name}`, authorization);
  }
} finally {
  report.finishedAt = new Date().toISOString();
  await mkdir(output, { recursive: true });
  await writeFile(new URL(`${mode}-${Date.now()}.json`, output), JSON.stringify(report, null, 2));
}
