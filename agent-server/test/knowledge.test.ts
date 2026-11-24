import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createKnowledgeIndex, knowledgeSources, type KnowledgeDocument } from '../src/knowledge.ts';

const knowledgeNow = Date.UTC(2026, 8, 23);
function knowledgeDocument(patch: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return { id: 'health_1', library: 'health', title: '测试资料：跑步活动',
    url: 'https://www.who.int/test-fixture', publishedAt: null,
    checkedAtMillis: knowledgeNow - 1000, expiresAtMillis: knowledgeNow + 1000,
    version: 1, status: 'active', spoiler: false, content: '测试正文：跑步与步行都是身体活动。', ...patch };
}
const request = { library: 'health' as const, query: '跑步', allowSpoilers: false, now: knowledgeNow };

test('双库选择检索两个库并保留来源类型与总数量上限', () => {
  const index = createKnowledgeIndex([knowledgeDocument(), knowledgeDocument({ id: 'lore_both', library: 'firefly', url: 'https://hsr.hoyoverse.com/en-us/', content: '跑步角色资料' })]);
  const result = index.search({ ...request, library: 'both' });
  assert.deepEqual(new Set(result.hits.map(hit => hit.source.library)), new Set(['health', 'firefly']));
  assert.ok(result.hits.length <= 3);
});

test('中文查询命中并保留真实来源字段，调用方修改不能污染索引', () => {
  const document = knowledgeDocument();
  const index = createKnowledgeIndex([document]);
  document.content = '篡改正文'; document.title = '篡改标题';
  const result = index.search(request);
  assert.equal(result.status, 'matched');
  assert.equal(result.hits[0]?.source.title, '测试资料：跑步活动');
  assert.equal(result.hits[0]?.source.url, 'https://www.who.int/test-fixture');
  assert.equal(result.hits[0]?.source.publishedAt, null);
  assert.equal(result.hits[0]?.source.version, 1);
  assert.ok(result.hits[0]?.excerpt.includes('步行'));
  assert.ok(Object.isFrozen(result.hits[0]?.source));
});

test('双库强制隔离，角色资料不能作为健康检索结果', () => {
  const index = createKnowledgeIndex([knowledgeDocument(), knowledgeDocument({ id: 'lore_1', library: 'firefly',
    url: 'https://hsr.hoyoverse.com/en-us/', title: '测试角色资料', content: '测试流萤角色与跑步。' })]);
  assert.deepEqual(index.search(request).hits.map(hit => hit.source.id), ['health_1']);
  assert.deepEqual(index.search({ ...request, library: 'firefly' }).hits.map(hit => hit.source.id), ['lore_1']);
});

test('撤回、过期、未来核验和默认剧透过滤，每次检索重新检查时效', () => {
  const index = createKnowledgeIndex([
    knowledgeDocument({ id: 'withdrawn', status: 'withdrawn' }),
    knowledgeDocument({ id: 'expired', expiresAtMillis: knowledgeNow }),
    knowledgeDocument({ id: 'future', checkedAtMillis: knowledgeNow + 1 }),
    knowledgeDocument({ id: 'spoiler', spoiler: true }),
  ]);
  const result = index.search(request);
  assert.equal(result.status, 'unavailable');
  assert.deepEqual(result.excluded, { withdrawn: 1, expired: 1, notYetValid: 1, spoiler: 1 });
  assert.deepEqual(index.search({ ...request, allowSpoilers: true }).hits.map(hit => hit.source.id), ['spoiler']);
  assert.equal(index.search({ ...request, allowSpoilers: true, now: knowledgeNow + 1000 }).status, 'unavailable');
});

test('空库、无匹配和仅标点查询不补造资料', () => {
  assert.equal(createKnowledgeIndex([]).search(request).status, 'unavailable');
  const index = createKnowledgeIndex([knowledgeDocument()]);
  for (const query of ['火星矿石', '!!!', '的']) {
    const result = index.search({ ...request, query });
    assert.equal(result.status, 'no_match'); assert.deepEqual(result.hits, []);
  }
});

test('超长段按 Unicode 字符切片，不拆代理对，最多返回三段且排序稳定', () => {
  const content = '跑步😊'.repeat(500);
  const index = createKnowledgeIndex([knowledgeDocument({ content })]);
  const first = index.search(request);
  assert.equal(first.hits.length, 3);
  assert.deepEqual(first, index.search(request));
  assert.equal(new Set(first.hits.map(hit => hit.citationId)).size, 3);
  for (const hit of first.hits) {
    assert.ok([...hit.excerpt].length <= 480);
    assert.equal(hit.excerpt.includes('\ufffd'), false);
    assert.equal(JSON.parse(JSON.stringify(hit.excerpt)), hit.excerpt);
  }
});

test('英文大小写和 Unicode 兼容字符规范化检索', () => {
  const index = createKnowledgeIndex([knowledgeDocument({ content: 'Walking is physical activity.' })]);
  assert.equal(index.search({ ...request, query: 'ＷＡＬＫＩＮＧ' }).status, 'matched');
});

test('资料撤回后重建索引不再返回旧片段，不从其他资料库补位', () => {
  const doc = knowledgeDocument();
  assert.equal(createKnowledgeIndex([doc]).search(request).status, 'matched');
  doc.status = 'withdrawn';
  const updated = createKnowledgeIndex([doc]);
  assert.deepEqual(updated.search(request).hits, []);
});

test('来源附录转义标题与链接括号，不允许元数据插入额外链接', () => {
  const index = createKnowledgeIndex([knowledgeDocument({ title: '[假链接](https://evil.example)\n**标题**',
    url: 'https://www.who.int/a(test)' })]);
  const footer = knowledgeSources(index.search(request));
  assert.equal(footer.includes('[假链接]'), false);
  assert.ok(footer.includes('(https://www.who.int/a%28test%29)'));
  assert.ok(footer.includes('发布：未提供'));
});

test('拒绝伪官方域名、错库来源、凭据 URL、查询参数和非 HTTPS', () => {
  for (const url of ['http://www.who.int/a', 'https://www.who.int.evil.example/a',
    'https://user:password@www.who.int/a', 'https://www.who.int/a?token=x',
    'https://hsr.hoyoverse.com/en-us/', 'https://www.who.int:8443/a']) {
    assert.throws(() => createKnowledgeIndex([knowledgeDocument({ url })]), /知识资料无效/);
  }
});

test('拒绝重复资料、畸形元数据、无效文本和无上限输入', () => {
  assert.throws(() => createKnowledgeIndex([knowledgeDocument(), knowledgeDocument()]));
  for (const patch of [{ version: 0 }, { content: '\ud800' }, { content: '<｜image｜>' },
    { content: '' }, { content: '字'.repeat(8001) }, { title: 'a'.repeat(81) },
    { publishedAt: '2026-02-30' }, { expiresAtMillis: knowledgeNow - 1001 },
    { status: 'unknown' }, { spoiler: 'true' }, { library: 'all' }, { tool: 'runs.list' }]) {
    assert.throws(() => createKnowledgeIndex([{ ...knowledgeDocument(), ...patch }]));
  }
  assert.throws(() => createKnowledgeIndex(Array(101).fill(knowledgeDocument())));
  assert.throws(() => createKnowledgeIndex(Array.from({ length: 9 }, (_, i) => knowledgeDocument({ id: `d${i}`, content: '字'.repeat(8000) }))));
  const index = createKnowledgeIndex([]);
  for (const patch of [{ library: 'all' }, { query: '字'.repeat(2001) }, { query: '\ud800' },
    { now: NaN }, { allowSpoilers: 'true' }]) assert.throws(() => index.search({ ...request, ...patch } as never));
});
