import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mcpDiscoveryRoute, parseMcpDiscoveryInput, parseMcpDiscoveryView, readMcpDiscoveryBody } from '../src/mcp-discovery.ts';
import fixture from '../../contracts/mcp-phone-v1/transport.json' with { type: 'json' };
import discoveryFixture from '../../contracts/mcp-phone-v1/discovery.json' with { type: 'json' };

const root = 'https://local.invalid/v1/agent/mcp/discoveries';
const { requestId, serviceId, revision, generation } = fixture.request;
const input = { requestId, serviceId, revision, generation };
const view = { ...input, version: 'mcp_discovery_v1', endpoint: fixture.request.endpoint,
  startedAtMillis: 1, deadlineAtMillis: 10001, status: 'running', resultRevision: null, reason: null };
const post = (body: BodyInit, headers = {}) => new Request(root, { method: 'POST',
  headers: { 'Content-Type': 'application/json', ...headers }, body });

test('原生与服务端共用发现输入和固定视图夹具', () => {
  assert.deepEqual(parseMcpDiscoveryInput(discoveryFixture.input), discoveryFixture.input);
  assert.deepEqual(parseMcpDiscoveryView(discoveryFixture.response.discovery), discoveryFixture.response.discovery);
});

test('发现操作只接受四个 UUID，不接受页面自报身份、端点、秘密或目录', () => {
  assert.deepEqual(parseMcpDiscoveryInput(input), input);
  for (const extra of [{ ownerId: 'owner' }, { endpoint: fixture.request.endpoint }, { token: 'secret' }, { catalog: [] }])
    assert.throws(() => parseMcpDiscoveryInput({ ...input, ...extra }), { code: 'invalid_request' });
  for (const key of Object.keys(input)) assert.throws(() => parseMcpDiscoveryInput({ ...input, [key]: 'invalid' }), { code: 'invalid_request' });
});

test('持久视图严格验证状态、时限和规范端点，损坏记录不能转成许可', () => {
  assert.deepEqual(parseMcpDiscoveryView(view), view);
  assert.equal(parseMcpDiscoveryView({ ...view, deadlineAtMillis: 25001 }).status, 'running');
  assert.equal(parseMcpDiscoveryView({ ...view, status: 'completed', resultRevision: revision }).status, 'completed');
  assert.equal(parseMcpDiscoveryView({ ...view, status: 'unconfirmed', reason: 'interrupted' }).status, 'unconfirmed');
  for (const patch of [{ status: 'completed' }, { resultRevision: revision }, { reason: 'interrupted' },
    { status: 'unconfirmed' }, { status: 'unconfirmed', reason: 'secret' }, { deadlineAtMillis: 10002 },
    { deadlineAtMillis: 30001 },
    { startedAtMillis: -1, deadlineAtMillis: 9999 }, { endpoint: 'http://tools.example.com/mcp' }, { secret: 'private' }])
    assert.throws(() => parseMcpDiscoveryView({ ...view, ...patch }), { code: 'unavailable' });
});

test('路由固定 HTTPS、路径和方法，不允许 GET 领取或查询串夹带凭据', () => {
  assert.deepEqual(mcpDiscoveryRoute(post('{}')), { kind: 'start' });
  assert.deepEqual(mcpDiscoveryRoute(new Request(`${root}/${requestId}`)), { kind: 'get', id: requestId });
  for (const kind of ['take', 'reply']) assert.deepEqual(mcpDiscoveryRoute(new Request(`${root}/${requestId}/${kind}`, { method: 'POST' })), { kind, id: requestId });
  for (const request of [new Request(root), new Request(`${root}/${requestId}/take`), new Request(`${root}/${requestId}`, { method: 'POST' }),
    new Request(`${root}/${requestId}?token=secret`), new Request(root.replace('https:', 'http:'), { method: 'POST' }),
    new Request(`${root}/not-uuid`), new Request(`${root}/${requestId}/call`, { method: 'POST' })])
    assert.throws(() => mcpDiscoveryRoute(request));
});

test('正文按实际字节限流并严格校验媒体类型、UTF-8 和空领取正文', async () => {
  const text = JSON.stringify(input);
  assert.deepEqual(await readMcpDiscoveryBody(post(text), { kind: 'start' }), input);
  await assert.rejects(readMcpDiscoveryBody(post(text + ' '.repeat(4096)), { kind: 'start' }), { status: 413 });
  await assert.rejects(readMcpDiscoveryBody(post(new Uint8Array([0x22, 0xc0, 0x80, 0x22])), { kind: 'start' }), { code: 'invalid_request' });
  for (const headers of [{ 'Content-Type': 'text/plain' }, { 'Content-Encoding': 'gzip' }])
    await assert.rejects(readMcpDiscoveryBody(post(text, headers), { kind: 'start' }), { status: 415 });
  await assert.rejects(readMcpDiscoveryBody(post('{}'), { kind: 'take', id: requestId }), { status: 400 });
  assert.equal(await readMcpDiscoveryBody(new Request(root, { method: 'POST' }), { kind: 'take', id: requestId }), undefined);
});

test('回执的 JSON 转义外壳和内部正文分别限流，不误拒绝合法转义', async () => {
  const reply = { ...fixture.reply, text: JSON.stringify({ value: '\u0000'.repeat(21000) }) };
  assert.ok(Buffer.byteLength(JSON.stringify(reply)) > 131072);
  assert.deepEqual(await readMcpDiscoveryBody(post(JSON.stringify(reply)), { kind: 'reply', id: requestId }), reply);
  await assert.rejects(readMcpDiscoveryBody(post(JSON.stringify({ ...reply, text: 'x'.repeat(131073) })),
    { kind: 'reply', id: requestId }), { code: 'invalid_request' });
});
