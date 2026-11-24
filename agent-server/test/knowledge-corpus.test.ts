import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CURATED_KNOWLEDGE } from '../src/knowledge-corpus.ts';
import { createKnowledgeIndex, type KnowledgeLibrary } from '../src/knowledge.ts';

const index = createKnowledgeIndex(CURATED_KNOWLEDGE);
const now = Date.UTC(2026, 9, 31);
const search = (library: KnowledgeLibrary, query: string, allowSpoilers = false) =>
  index.search({ library, query, allowSpoilers, now });

test('真实语料题集命中基础介绍、别名、寄语与健康摘要', () => {
  for (const query of ['流萤是谁', '流螢', 'Firefly', '萤火虫意象']) {
    const result = search('firefly', query);
    assert.equal(result.status, 'matched', query);
    assert.ok(result.hits.every(hit => hit.source.id === 'firefly_official_profile'));
  }
  assert.equal(search('health', '步行和久坐').hits[0]?.source.id, 'who_physical_activity');
});

test('身份身世默认不可检索，允许剧透后才命中', () => {
  for (const query of ['萨姆', 'SAM', '失熵', '星核猎手']) {
    assert.equal(search('firefly', query).status, 'no_match', query);
    assert.equal(search('firefly', query, true).hits[0]?.source.id, 'firefly_official_story', query);
  }
});

test('健康与角色分库，未收录的词项不会伪造来源', () => {
  assert.equal(search('health', '星核猎手', true).status, 'no_match');
  assert.equal(search('firefly', '步行和久坐', true).status, 'no_match');
  assert.equal(search('firefly', '量子纠缠', true).status, 'no_match');
});

test('真实目录的角色来源、期限与未知发布日期保持明确', () => {
  const characters = CURATED_KNOWLEDGE.filter(doc => doc.library === 'firefly');
  assert.equal(characters.length, 2);
  for (const doc of characters) {
    assert.equal(doc.url, 'https://hsr.hoyoverse.com/zh-tw/character');
    assert.equal(doc.publishedAt, null);
    assert.equal(doc.expiresAtMillis - doc.checkedAtMillis, 30 * 86400000);
  }
  assert.equal(index.search({ library: 'firefly', query: '流萤', allowSpoilers: true,
    now: characters[0]!.expiresAtMillis }).status, 'unavailable');
});
