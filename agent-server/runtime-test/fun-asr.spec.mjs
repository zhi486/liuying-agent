import { env } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, listDurableObjectIds } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => vi.unstubAllGlobals());
const bindings = () => ({ ...env, FUN_ASR_API_KEY: 'fixture-only', FUN_ASR_WORKSPACE_ID: 'fixture',
  FUN_ASR_REGION: 'cn-beijing', FUN_ASR_VOCABULARY_ID: 'fixture-vocabulary' });
async function device() {
  const owner = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros,halted) VALUES (?,1,1)').bind(owner).run();
  return { owner, ...await createDeviceAdmin({ db: env.DB }).issue(owner) };
}
const request = (token, headers = {}) => new Request('https://local.invalid/v1/agent/voice/stream', {
  headers: { Authorization: `Bearer ${token}`, Upgrade: 'websocket', 'X-Voice-Consent': 'fun-asr-v1', ...headers },
});
function provider() {
  let socket, task;
  const frames = [], commands = [], closed = vi.fn();
  const send = (event, sentence) => socket.send(JSON.stringify({ header: { event, task_id: task },
    payload: { output: { sentence } } }));
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    // 真实运行时必须能构造请求；纯 fetch 替身会漏过不支持的重定向模式。
    new Request(url, init);
    expect(url).toBe('https://fixture.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference');
    expect(init.headers.Authorization).toBe('Bearer fixture-only');
    expect(init.redirect).toBe('manual');
    const pair = new WebSocketPair(); socket = pair[1]; socket.accept(); socket.binaryType = 'arraybuffer';
    socket.addEventListener('close', closed);
    socket.addEventListener('message', event => {
      if (typeof event.data !== 'string') { frames.push(event.data.byteLength); return; }
      const value = JSON.parse(event.data); commands.push(value); task = value.header.task_id;
      if (value.header.action === 'run-task') send('task-started');
      else { send('task-finished'); socket.close(1000); }
    });
    return new Response(null, { status: 101, webSocket: pair[0] });
  }));
  return { frames, commands, closed, send };
}
async function open(token) {
  const response = await worker.fetch(request(token), bindings(), createExecutionContext());
  expect(response.status).toBe(101);
  const client = response.webSocket, events = [];
  client.accept(); client.addEventListener('message', event => { events.push(JSON.parse(event.data)); });
  await vi.waitFor(() => expect(events).toContainEqual({ type: 'ready' }));
  return { client, events };
}

it('流式正式入口替换中间句并定稿，不创建Agent任务或DO', async () => {
  const identity = await device(), fixture = provider();
  const before = (await listDurableObjectIds(env.AGENT_RUNTIME)).length;
  const { client, events } = await open(identity.token);
  try {
    expect(fixture.commands[0].payload.parameters.vocabulary_id).toBe('fixture-vocabulary');
    client.send(new Uint8Array(3200));
    await vi.waitFor(() => expect(fixture.frames).toEqual([3200]));
    fixture.send('result-generated', { sentence_id: 1, sentence_end: false, text: '青松' });
    fixture.send('result-generated', { sentence_id: 1, sentence_end: true, text: '轻松跑' });
    await vi.waitFor(() => expect(events.at(-1)).toEqual({ type: 'preview', text: '轻松跑' }));
    client.send('{"type":"finish"}');
    await vi.waitFor(() => expect(events.at(-1)).toEqual({ type: 'completed', text: '轻松跑' }));
    await vi.waitFor(() => expect(fixture.closed).toHaveBeenCalled());
    expect(fixture.commands).toHaveLength(2);
    expect((await listDurableObjectIds(env.AGENT_RUNTIME)).length).toBe(before);
    expect((await env.DB.prepare('SELECT COUNT(*) count FROM agent_cloud_tasks WHERE owner_id=?').bind(identity.owner).first()).count).toBe(0);
  } finally { client.close(); }
});

it('缺少授权、同意或热词配置时不建立供应商连接', async () => {
  const identity = await device(); const upstream = vi.fn(); vi.stubGlobal('fetch', upstream);
  for (const [req, config, status] of [
    [request('invalid'), bindings(), 401],
    [request(identity.token, { 'X-Voice-Consent': 'baidu-asr-v1' }), bindings(), 400],
    [request(identity.token, { Origin: 'https://local.invalid' }), bindings(), 400],
    [request(identity.token), { ...bindings(), FUN_ASR_VOCABULARY_ID: '' }, 503],
  ]) expect((await worker.fetch(req, config, createExecutionContext())).status).toBe(status);
  expect(upstream).not.toHaveBeenCalled();
});

it('供应商重定向或非升级响应均失败且不会跟随转发凭据', async () => {
  const identity = await device();
  for (const status of [302, 401, 503]) {
    const upstream = vi.fn(async (url, init) => {
      const outbound = new Request(url, init);
      expect(outbound.redirect).toBe('manual');
      return new Response(null, { status, headers: { Location: 'https://unexpected.invalid/' } });
    });
    vi.stubGlobal('fetch', upstream);
    const result = await worker.fetch(request(identity.token), bindings(), createExecutionContext());
    expect(result.status).toBe(503);
    expect(upstream).toHaveBeenCalledTimes(1);
  }
});

it('用户断开或帧无效时关闭供应商，不自动重连', async () => {
  for (const cancel of [true, false]) {
    const identity = await device(), fixture = provider();
    const { client, events } = await open(identity.token);
    if (cancel) client.close(1000); else client.send(new Uint8Array(3));
    await vi.waitFor(() => expect(fixture.closed).toHaveBeenCalled());
    if (!cancel) expect(events).toContainEqual({ type: 'error' });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fixture.frames).toHaveLength(0);
    client.close();
  }
});

it('设备撤销后结束帧不再转发，不返回最终文字', async () => {
  const identity = await device(), fixture = provider();
  const { client, events } = await open(identity.token);
  try {
    client.send(new Uint8Array(3200));
    await vi.waitFor(() => expect(fixture.frames).toEqual([3200]));
    await createDeviceAdmin({ db: env.DB }).revoke(identity.owner, identity.deviceId);
    client.send('{"type":"finish"}');
    await vi.waitFor(() => expect(events.at(-1)).toEqual({ type: 'error' }));
    await vi.waitFor(() => expect(fixture.closed).toHaveBeenCalled());
    expect(fixture.commands).toHaveLength(1);
    expect(events.some(event => event.type === 'completed')).toBe(false);
  } finally { client.close(); }
});
