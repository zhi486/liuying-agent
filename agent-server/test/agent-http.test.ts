import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseHttpRequest, errorResponse, taskResponse } from '../src/agent-http.ts';
import { CloudError, type CloudTaskView } from '../src/cloud-task-contracts.ts';
import { input } from './execution-fixture.ts';

const root = 'https://agent.invalid/v1/agent';
const id = '17f23bb3-25a0-4412-a57d-5678c74b720a';
const submit = (body: BodyInit, headers: HeadersInit = { 'content-type': 'application/json' }) =>
  new Request(`${root}/tasks`, { method: 'POST', body, headers });
const rejected = async (request: Request, status: number, code = 'invalid_request') => {
  try { await parseHttpRequest(request); assert.fail('应拒绝该请求'); }
  catch (error) {
    const response = errorResponse(error);
    assert.equal(response.status, status);
    assert.deepEqual(await response.json(), { error: code });
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.has('access-control-allow-origin'), false);
  }
};

test('HTTP 任务路由只接受规范路径，提交正文继续使用正式任务校验', async () => {
  const data = input();
  assert.deepEqual(await parseHttpRequest(submit(JSON.stringify(data))), { kind: 'submit', input: data });
  assert.deepEqual(await parseHttpRequest(new Request(`${root}/tasks/${id}`)), { kind: 'get', id });
  assert.deepEqual(await parseHttpRequest(new Request(`${root}/requests/${id}`)), { kind: 'find', id });
  for (const kind of ['cancel', 'withdraw', 'acknowledge']) {
    assert.deepEqual(await parseHttpRequest(new Request(`${root}/tasks/${id}/${kind}`, { method: 'POST' })), { kind, id });
  }
  await rejected(submit(JSON.stringify({ ...data, ownerId: '注入' })), 400);
  await rejected(submit(JSON.stringify({ ...data, prompt: '\ud800' })), 400);
});

test('未知路径/方法/标识、query、编码路径和明文传输被拒绝', async () => {
  for (const path of ['/tasks/', '/admin', `/tasks/${id}/claim`, `/tasks/${id}/finish`])
    await rejected(new Request(root + path), 404, 'not_found');
  await rejected(new Request(`${root}/tasks`, { method: 'DELETE' }), 400);
  await rejected(new Request(`${root}/tasks/not-a-uuid`), 400);
  await rejected(new Request(`${root}/tasks/${id.toUpperCase()}`), 400);
  await rejected(new Request(`${root}/tasks/${id}?token=private`), 400);
  await rejected(new Request(`${root}/%74asks/${id}`), 404, 'not_found');
  await rejected(new Request(`http://agent.invalid/v1/agent/tasks/${id}`), 400);
});

test('媒体类型、压缩、非法 UTF-8/JSON 和非空控制正文拒绝且不泄露内容', async () => {
  for (const type of ['text/plain', 'application/json; charset=gbk', 'application/json; other=x'])
    await rejected(submit('私密正文', { 'content-type': type }), 415);
  await rejected(submit('{}', { 'content-type': 'application/json', 'content-encoding': 'gzip' }), 415);
  await rejected(submit(new Uint8Array([0x22, 0xc0, 0x80, 0x22])), 400);
  await rejected(submit('私密正文不是 JSON'), 400);
  await rejected(new Request(`${root}/tasks/${id}/cancel`, { method: 'POST', body: ' ' }), 400);
  const data = input();
  assert.equal((await parseHttpRequest(submit(JSON.stringify(data), { 'content-type': 'application/json; charset=UTF-8' }))).kind, 'submit');
});

test('按请求键清理仅接受规范 UUID 和空 POST 正文', async () => {
  assert.deepEqual(await parseHttpRequest(new Request(`${root}/requests/${id}/purge`, { method: 'POST' })), { kind: 'purge', id });
  await rejected(new Request(`${root}/requests/${id}/purge`), 400);
  await rejected(new Request(`${root}/requests/${id}/purge`, { method: 'POST', body: '{}' }), 400);
  await rejected(new Request(`${root}/requests/${id}/purge`, { method: 'POST', body: ' ' }), 400);
  await rejected(new Request(`${root}/requests/not-uuid/purge`, { method: 'POST' }), 400);
  await rejected(new Request(`${root}/tasks/${id}/purge`, { method: 'POST' }), 404, 'not_found');
});

test('读取真实字节而非 Content-Length，恰好 64 KiB 接受，再多一字节拒绝', async () => {
  const data = JSON.stringify(input());
  const body = data + ' '.repeat(65536 - Buffer.byteLength(data));
  assert.equal((await parseHttpRequest(submit(body))).kind, 'submit');
  await rejected(submit(body + ' ', { 'content-type': 'application/json', 'content-length': '1' }), 413);
  let cancelled = false;
  const request = new Request(`${root}/tasks`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: new ReadableStream({
      start(controller) { controller.enqueue(new Uint8Array(32768)); controller.enqueue(new Uint8Array(32769)); },
      cancel() { cancelled = true; },
    }), duplex: 'half',
  } as RequestInit);
  await rejected(request, 413);
  assert.equal(cancelled, true);
});

test('静态错误映射与任务回执状态，不暴露原异常', async () => {
  const codes = { invalid_request: 400, unauthenticated: 401, not_found: 404, conflict: 409,
    busy: 409, inactive: 409, request_expired: 410, unavailable: 503 } as const;
  for (const [code, status] of Object.entries(codes)) {
    const response = errorResponse(new CloudError(code as keyof typeof codes));
    assert.equal(response.status, status);
    assert.deepEqual(await response.json(), { error: code });
  }
  assert.deepEqual(await errorResponse(new Error('私密 SQL/令牌')).json(), { error: 'unavailable' });
  const task: CloudTaskView = { id, status: 'queued', reason: null, acceptedAtMillis: 1,
    deadlineAtMillis: 30001, updatedAtMillis: 1, resultState: 'none', result: null };
  assert.equal(taskResponse(task, true).status, 202);
  assert.equal(taskResponse({ ...task, status: 'completed' }, true).status, 200);
  const response = taskResponse(task);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), { task });
});

test('读正文时断开会释放 reader，即使底层 cancel 拒绝也不泄露异常', async () => {
  const controller = new AbortController();
  let cancelled = false;
  const request = new Request(`${root}/tasks`, { method: 'POST', signal: controller.signal,
    headers: { 'content-type': 'application/json' },
    body: new ReadableStream({ cancel() { cancelled = true; return Promise.reject(new Error('私密底层错误')); } }),
    duplex: 'half',
  } as RequestInit);
  const result = rejected(request, 400);
  controller.abort(); await result;
  assert.equal(cancelled, true);
  assert.equal(request.body?.locked, false);
});
