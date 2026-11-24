import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseSubmission } from '../src/cloud-task-contracts.ts';
import { parseHttpRequest, errorResponse } from '../src/agent-http.ts';
import { parseMcpTaskDecision } from '../src/mcp-task-contracts.ts';
import { fixture, input } from './execution-fixture.ts';
import { readFileSync } from 'node:fs';

const id = () => crypto.randomUUID();
const configuration = () => ({ version: 'native_mcp_v1', services: [{ serviceId: id(), revision: id(), generation: id() }] });
const submission = () => ({ ...input(), conversation: { version: 'conversation_v1', id: id(), approved: true, messages: [] },
  mcpTools: configuration() });
const decision = () => ({ version: 'native_mcp_v1', invocationId: id(), intentDigest: 'a'.repeat(64), generation: id(), decision: 'approve' });
const root = `https://local.invalid/v1/agent/tasks/${id()}/mcp`;

test('手机与服务端共用正式任务配置和确认夹具，实际参数摘要一致', async () => {
  const data = JSON.parse(readFileSync(new URL('../../contracts/mcp-phone-v1/task.json', import.meta.url), 'utf8'));
  assert.deepEqual(parseSubmission(data.submission).task.mcpTools, data.submission.mcpTools);
  assert.deepEqual(parseMcpTaskDecision(data.decision), data.decision);
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data.confirmation.argumentsJson)));
  assert.equal(Array.from(hash, byte => byte.toString(16).padStart(2, '0')).join(''), data.confirmation.argumentsDigest);
  assert.equal(data.confirmation.requestId, data.submission.clientRequestId);
  assert.equal(data.confirmation.generation, data.submission.mcpTools.services[0].generation);
});

test('任务插件仅接收本次选定的服务版本和本机代际，旧任务不补字段', () => {
  const old = parseSubmission(input());
  assert.equal(Object.hasOwn(old.task, 'mcpTools'), false);
  const data = submission();
  const parsed = parseSubmission(data);
  data.mcpTools.services[0]!.generation = id();
  assert.notEqual(parsed.task.mcpTools!.services[0]!.generation, data.mcpTools.services[0]!.generation);
  assert.equal(Object.isFrozen(parsed.task.mcpTools!.services[0]), true);
  const { conversation: _conversation, ...withoutConversation } = submission();
  assert.throws(() => parseSubmission(withoutConversation));
  for (const config of [null, {}, { ...configuration(), services: [] },
    { ...configuration(), services: Array(9).fill(configuration().services[0]) },
    { ...configuration(), approved: true }, { ...configuration(), services: [{ ...configuration().services[0], apiKey: '不允许' }] },
    { ...configuration(), services: [{ ...configuration().services[0], generation: '无效' }] }])
    assert.throws(() => parseSubmission({ ...submission(), mcpTools: config }));
  const duplicate = configuration(); duplicate.services.push({ ...duplicate.services[0]! });
  assert.throws(() => parseSubmission({ ...submission(), mcpTools: duplicate }));
});

test('插件服务／版本／代际进入正式请求指纹，旧请求键不能换许可', async t => {
  const f = await fixture(t); const data = submission();
  await f.tasks.cancel(f.header, f.execution.taskId);
  const accepted = await f.tasks.accept(f.header, data);
  assert.equal((await f.tasks.accept(f.header, data)).task.id, accepted.task.id);
  for (const field of ['serviceId', 'revision', 'generation'] as const) {
    const changed = structuredClone(data); changed.mcpTools.services[0]![field] = id();
    await assert.rejects(f.tasks.accept(f.header, changed), { code: 'conflict' });
  }
  const { mcpTools: _mcp, ...withoutMcp } = data;
  await assert.rejects(f.tasks.accept(f.header, withoutMcp), { code: 'conflict' });
});

test('确认只接受完整意图摘要和代际，不接受模型自报布尔授权', () => {
  const data = decision(); assert.deepEqual(parseMcpTaskDecision(data), data);
  for (const bad of [{ ...data, approved: true }, { ...data, decision: true }, { ...data, intentDigest: 'A'.repeat(64) },
    { ...data, generation: null }, { ...data, invocationId: '无效' }, { confirmed: true }])
    assert.throws(() => parseMcpTaskDecision(bad), { code: 'invalid_request' });
});

test('只读 GET 不领取交付；take／close 必须空正文 POST，回执及确认严格定型', async () => {
  assert.equal((await parseHttpRequest(new Request(root))).kind, 'mcp');
  for (const action of ['take', 'close']) {
    const route = await parseHttpRequest(new Request(`${root}/${action}`, { method: 'POST' }));
    assert.equal(route.kind === 'mcp' && route.action, action);
  }
  const data = decision();
  const request = (path: string, body: unknown) => new Request(path, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const parsed = await parseHttpRequest(request(`${root}/decision`, data));
  assert.equal(parsed.kind === 'mcp' && parsed.action, 'decision');
  for (const req of [new Request(`${root}/take`), new Request(`${root}/close`), request(`${root}/take`, {}),
    request(root, {}), request(`${root}/decision`, { ...data, confirmed: true }), request(`${root}/reply`, { status: 200 }),
    new Request(root + '?generation=secret'), new Request(`${root}/decision`, { method: 'POST', body: '{}' })]) {
    await assert.rejects(parseHttpRequest(req), error => [400, 415].includes(errorResponse(error).status));
  }
});
