import { env } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext, listDurableObjectIds } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => vi.unstubAllGlobals());

it('语音正式入口认证后单次转发PCM，回填文字但不创建Agent任务或DO', async () => {
  const owner = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros,halted) VALUES (?,1,1)').bind(owner).run();
  const device = await createDeviceAdmin({ db: env.DB }).issue(owner);
  const bindings = { ...env, BAIDU_ASR_API_KEY: 'fixture', BAIDU_ASR_SECRET_KEY: 'fixture' };
  const request = token => new Request('https://local.invalid/v1/agent/voice', { method: 'POST',
    headers: { Authorization: token, 'Content-Type': 'audio/pcm;rate=16000', 'X-Voice-Consent': 'baidu-asr-v1' },
    body: new Uint8Array(3200) });
  const upstream = vi.fn(async (url, init) => {
    expect(init.redirect).toBe('error');
    if (url === 'https://aip.baidubce.com/oauth/2.0/token') return Response.json({ access_token: 'fixture-token', expires_in: 2592000 });
    expect(url).toBe('https://vop.baidu.com/server_api');
    const body = JSON.parse(init.body);
    expect(body).toMatchObject({ format: 'pcm', rate: 16000, channel: 1, len: 3200, dev_pid: 1537 });
    expect(atob(body.speech).length).toBe(3200);
    return Response.json({ err_no: 0, result: ['今晚轻松跑'] });
  });
  vi.stubGlobal('fetch', upstream);
  const ctx = createExecutionContext();
  const before = (await listDurableObjectIds(env.AGENT_RUNTIME)).length;
  expect((await worker.fetch(request('invalid'), bindings, ctx)).status).toBe(401);
  expect(upstream).not.toHaveBeenCalled();
  expect((await worker.fetch(request(`Bearer ${device.token}`), env, ctx)).status).toBe(503);
  expect(upstream).not.toHaveBeenCalled();
  const response = await worker.fetch(request(`Bearer ${device.token}`), bindings, ctx);
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ text: '今晚轻松跑' });
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(upstream).toHaveBeenCalledTimes(2);
  const warmResponse = await worker.fetch(request(`Bearer ${device.token}`), bindings, ctx);
  expect(warmResponse.status).toBe(200);
  expect(await warmResponse.json()).toEqual({ text: '今晚轻松跑' });
  expect(upstream).toHaveBeenCalledTimes(3);
  expect((await listDurableObjectIds(env.AGENT_RUNTIME)).length).toBe(before);
  expect((await env.DB.prepare('SELECT COUNT(*) count FROM agent_cloud_tasks WHERE owner_id=?').bind(owner).first()).count).toBe(0);
  await waitOnExecutionContext(ctx);
});
