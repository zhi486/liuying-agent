import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseTask } from '../src/tools.ts';
import { parseResult, parseSubmission } from '../src/cloud-task-contracts.ts';
import { createAgentService } from '../src/agent-service.ts';
import { runAgent } from '../src/loop.ts';
import { fixture, input, rate } from './execution-fixture.ts';

const knowledge = { version: 'curated_knowledge_v1', library: 'firefly', allowSpoilers: false };
test('知识选择严格解析、复制冻结，不为旧任务补默认字段', () => {
  const submission = { ...input(), knowledge: { ...knowledge } };
  const parsed = parseSubmission(submission);
  submission.knowledge.allowSpoilers = true;
  assert.equal(parsed.task.knowledge?.allowSpoilers, false);
  assert.ok(Object.isFrozen(parsed.task.knowledge));
  assert.equal(Object.hasOwn(parseSubmission(input()).task, 'knowledge'), false);
  for (const value of [null, {}, { ...knowledge, version: 'v2' }, { ...knowledge, library: 'all' },
    { ...knowledge, allowSpoilers: 'true' }, { ...knowledge, library: 'health', allowSpoilers: true },
    { ...knowledge, url: 'https://evil.example' }, { ...knowledge, content: '指令' }]) {
    assert.throws(() => parseSubmission({ ...input(), knowledge: value }));
  }
});

test('结构化来源严格校验，不接受任意网站、重复或畸形元数据', () => {
  const source = { citationId: 'official_v1_1', title: '官方资料', url: 'https://hsr.hoyoverse.com/zh-tw/character', publishedAt: null, checkedAtMillis: 1000 };
  const result = { status: 'completed', answer: '回答', actions: [], sources: [source] };
  assert.deepEqual(parseResult(result), result);
  for (const patch of [{ url: 'javascript:alert(1)' }, { url: 'https://hsr.hoyoverse.com.evil.test/' },
    { url: 'https://user@hsr.hoyoverse.com/' }, { title: '\n' }, { checkedAtMillis: -1 },
    { publishedAt: '2026-02-30' }, { content: '偷偷附带' }]) {
    assert.throws(() => parseResult({ ...result, sources: [{ ...source, ...patch }] }));
  }
  assert.throws(() => parseResult({ ...result, sources: [source, source] }));
});

test('任务要求知识而宿主未接线时拒绝，不静默退回无资料回答', async () => {
  const task = parseSubmission({ ...input(), knowledge }).task;
  let called = false;
  const result = await runAgent(parseTask(task), {
    model: { async generate() { called = true; throw new Error(); } },
    budget: { async reserve() { throw new Error(); }, async settle() {} },
  });
  assert.equal(result.status, 'failed');
  assert.equal(called, false);
});

test('正式服务冻结知识选择、拒绝同键换库或剧透，保存结构化来源并幂等恢复', async t => {
  const f = await fixture(t);
  await f.tasks.cancel(f.header, f.execution.taskId);
  // 执行账本使用夹具时钟；语料有效性单独固定到核验后一天。
  t.mock.method(Date, 'now', () => Date.UTC(2026, 9, 31));
  let sent = ''; let calls = 0;
  const service = createAgentService({ db: f.database.db, now: f.now, rate, apiKey: 'fixture-only', countInputTokens: async () => 200,
    fetch: async (_url, init) => {
      sent = String(init?.body);
      if (++calls === 1) return Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls', message: { role: 'assistant', content: null,
        tool_calls: [{ id: 'knowledge_1', type: 'function', function: { name: 'knowledge_search', arguments: '{"query":"流萤"}' } }] } }],
        usage: { prompt_tokens: 200, completion_tokens: 20, total_tokens: 220 } });
      return Response.json({ choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '流萤的英文名是 Firefly。' } }],
        usage: { prompt_tokens: 200, completion_tokens: 20, total_tokens: 220 } });
    } });
  const original = { ...input(), prompt: '流萤是谁', knowledge: { ...knowledge } };
  const retry = structuredClone(original);
  const pending = service.prepare(f.header, original);
  original.knowledge.allowSpoilers = true;
  const prepared = await pending;
  assert.equal((await service.prepare(f.header, retry)).run, null);
  await assert.rejects(service.prepare(f.header, { ...retry, knowledge: { ...knowledge, allowSpoilers: true } }), /conflict/);
  await assert.rejects(service.prepare(f.header, { ...retry, knowledge: { ...knowledge, library: 'health' } }), /conflict/);
  await prepared.run!();
  assert.equal(calls, 2);
  assert.ok(sent.includes('curated_knowledge_v1'));
  assert.ok(!sent.includes('firefly_official_story'));
  const result = (await service.get(f.header, prepared.task.id)).result;
  assert.equal(result?.status, 'completed');
  assert.ok(result?.status === 'completed');
  assert.ok(result.sources!.length > 0);
  assert.ok(result.sources!.every(source => source.citationId.startsWith('firefly_official_profile')));
  assert.ok(!result.answer.includes('本次检索来源'));
  const restarted = createAgentService({ db: f.database.db, now: f.now, rate, apiKey: 'fixture-only', countInputTokens: async () => 200 });
  assert.deepEqual((await restarted.get(f.header, prepared.task.id)).result, result);
});
