import assert from 'node:assert/strict';
import { test, type TestContext } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { voiceInput } from '../src/voice-input.ts';
import { createBaiduTokenCache } from '../src/baidu-token-cache.ts';

async function fixture(t: TestContext) {
  const database = openBudgetDatabase(); t.after(database.close);
  database.sqlite.exec("INSERT INTO agent_budget_accounts VALUES ('owner',1,1)");
  const device = await createDeviceAdmin({ db: database.db }).issue('owner');
  const env = { DB: database.db, BAIDU_ASR_API_KEY: 'synthetic-key', BAIDU_ASR_SECRET_KEY: 'synthetic-secret' };
  const request = (body: Uint8Array = new Uint8Array(3200), headers: Record<string, string> = {}) =>
    new Request('https://agent.invalid/v1/agent/voice', { method: 'POST', body: new Uint8Array(body).buffer,
      headers: { Authorization: `Bearer ${device.token}`, 'Content-Type': 'audio/pcm;rate=16000',
        'X-Voice-Consent': 'baidu-asr-v1', ...headers } });
  return { database, device, env, request };
}

test('经认证且单次同意的PCM只识别，不创建Agent任务；超预算仍可用', async t => {
  const f = await fixture(t); const sent: { url: string; body: string }[] = [];
  const provider: typeof fetch = async (url, init) => {
    sent.push({ url: String(url), body: String(init?.body) });
    assert.equal(init?.redirect, 'error');
    return Response.json(sent.length === 1 ? { access_token: 'synthetic-token' } : { err_no: 0, result: ['今晚轻松跑'] });
  };
  const response = await voiceInput(f.request(), f.env, provider);
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { text: '今晚轻松跑' });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(sent.length, 2);
  assert.equal(sent[0]!.url, 'https://aip.baidubce.com/oauth/2.0/token');
  assert.equal(sent[1]!.url, 'https://vop.baidu.com/server_api');
  const body = JSON.parse(sent[1]!.body);
  assert.equal(body.dev_pid, 1537); assert.equal(body.rate, 16000); assert.equal(body.channel, 1);
  assert.equal(body.len, 3200); assert.equal(Buffer.from(body.speech, 'base64').length, 3200);
  assert.equal(f.database.sqlite.prepare('SELECT COUNT(*) n FROM agent_cloud_tasks').get()!.n, 0);
});

test('无授权、无同意、非法格式、超长音频及缺少配置均不请求百度', async t => {
  const f = await fixture(t); let calls = 0;
  const provider: typeof fetch = async () => { calls++; throw new Error('不应请求'); };
  for (const [request, env, expected] of [
    [f.request(undefined, { Authorization: 'invalid' }), f.env, 401],
    [f.request(undefined, { 'X-Voice-Consent': '' }), f.env, 400],
    [f.request(undefined, { 'Content-Type': 'audio/wav' }), f.env, 400],
    [f.request(new Uint8Array(960002)), f.env, 413],
    [f.request(new Uint8Array(3201)), f.env, 400],
    [f.request(new Uint8Array(2)), f.env, 400],
    [f.request(), { DB: f.env.DB }, 503],
  ] as const) assert.equal((await voiceInput(request, env, provider)).status, expected);
  assert.equal(calls, 0);
});

test('供应商错误和恶意大响应不泄露正文或密钥且不重试', async t => {
  const f = await fixture(t);
  for (const result of [{ err_no: 3301, err_msg: 'synthetic-secret' }, { err_no: 0, result: [' '] },
    { err_no: 0, result: ['字'.repeat(2001)] }]) {
    let calls = 0;
    const response = await voiceInput(f.request(), f.env, async () => Response.json(++calls === 1 ? { access_token: 'synthetic-token' } : result));
    assert.equal(response.status, 503); assert.deepEqual(await response.json(), { error: 'unavailable' });
    assert.equal(calls, 2);
  }
  let calls = 0;
  const response = await voiceInput(f.request(), f.env, async () => { calls++; return new Response('x'.repeat(16385)); });
  assert.equal(response.status, 413); assert.equal(calls, 1);
});

test('获取供应商令牌后设备被撤销，不再上传音频', async t => {
  const f = await fixture(t); let calls = 0;
  const response = await voiceInput(f.request(), f.env, async () => {
    calls++;
    await createDeviceAdmin({ db: f.env.DB }).revoke('owner', f.device.deviceId);
    return Response.json({ access_token: 'synthetic-token' });
  });
  assert.equal(response.status, 401); assert.equal(calls, 1);
});

test('暖请求省去令牌网络往返，缓存命中仍逐次鉴权且故障不重发音频', async t => {
  const f = await fixture(t);
  const cache = createBaiduTokenCache();
  let authCalls = 0, asrCalls = 0, fail = false;
  const provider: typeof fetch = async url => {
    if (String(url).includes('/oauth/')) {
      authCalls++;
      return Response.json({ access_token: `synthetic-${authCalls}`, expires_in: 2592000 });
    }
    asrCalls++;
    return Response.json(fail ? { err_no: 3302 } : { err_no: 0, result: ['今晚轻松跑'] });
  };
  for (let index = 0; index < 2; index++) assert.equal((await voiceInput(f.request(), f.env, provider, cache)).status, 200);
  assert.equal(authCalls, 1); assert.equal(asrCalls, 2);
  assert.equal((await voiceInput(f.request(undefined, { Authorization: 'invalid' }), f.env, provider, cache)).status, 401);
  assert.equal(asrCalls, 2);
  fail = true;
  assert.equal((await voiceInput(f.request(), f.env, provider, cache)).status, 503);
  assert.equal(authCalls, 1); assert.equal(asrCalls, 3);
  fail = false;
  assert.equal((await voiceInput(f.request(), f.env, provider, cache)).status, 200);
  assert.equal(authCalls, 2); assert.equal(asrCalls, 4);
  assert.equal((await voiceInput(f.request(), { ...f.env, BAIDU_ASR_SECRET_KEY: 'rotated-secret' }, provider, cache)).status, 200);
  assert.equal(authCalls, 3); assert.equal(asrCalls, 5);
});
