import { describe, it, expect } from 'vitest';
import { createKnowledgeIndex } from '../src/knowledge.ts';
import { CURATED_KNOWLEDGE } from '../src/knowledge-corpus.ts';
import { runAgent } from '../src/loop.ts';
import { parseResult } from '../src/cloud-task-contracts.ts';

describe('精选知识在本地 workerd 中运行', () => {
  it('手机显式选择返回可序列化来源，拒绝宿主丢失选择', async () => {
    const now = Date.now();
    const index = createKnowledgeIndex(CURATED_KNOWLEDGE.map(doc => ({ ...doc,
      checkedAtMillis: now - 1000, expiresAtMillis: now + 60000 })));
    const task = { taskId: 'runtime_mobile_rag', prompt: '流萤是谁', consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
      knowledge: { version: 'curated_knowledge_v1', library: 'firefly', allowSpoilers: false } };
    let calls = 0;
    const ports = {
      model: { async generate() { calls++; return { decision: { kind: 'final', text: '合成回答' }, usage: { inputTokens: 100, outputTokens: 10 } }; } },
      budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
    };
    const result = await runAgent(task, ports, { knowledge: { index, library: 'firefly' } });
    expect(result.status).toBe('completed');
    expect(result.sources.length).toBeGreaterThan(0);
    expect(result.sources.every(source => source.citationId.startsWith('firefly_official_profile'))).toBe(true);
    expect(parseResult(JSON.parse(JSON.stringify(result)))).toEqual(result);
    expect((await runAgent(task, ports)).status).toBe('failed');
    expect(calls).toBe(1);
  });
  it('中文索引、用户级上下文和来源附录可用，不请求外部网络', async () => {
    const now = Date.now();
    const index = createKnowledgeIndex(CURATED_KNOWLEDGE.map(doc => ({ ...doc,
      checkedAtMillis: now - 1000, expiresAtMillis: now + 60000 })));
    let calls = 0;
    const result = await runAgent({ taskId: 'runtime_rag', prompt: '步行算身体活动吗',
      consent: { cloudProcessing: true, runSummaries: false }, summaries: [] }, {
      model: { async generate(request) {
        calls++;
        expect(request.messages[0].role).toBe('user');
        expect(request.messages[0].text).toContain('curated_knowledge_v1');
        return { decision: { kind: 'final', text: '步行属于身体活动。' }, usage: { inputTokens: 100, outputTokens: 10 } };
      } },
      budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
    }, { knowledge: { index, library: 'health' } });
    expect(result.status).toBe('completed');
    expect(result.answer).toContain('https://www.who.int/news-room/fact-sheets/detail/physical-activity');
    expect(calls).toBe(1);
    const character = index.search({ library: 'firefly', query: '流萤', allowSpoilers: false, now });
    expect(character.status).toBe('matched');
    expect(character.hits.every(hit => !hit.source.spoiler)).toBe(true);
    expect(character.excluded.spoiler).toBe(1);
    expect(index.search({ library: 'firefly', query: '萨姆', allowSpoilers: false, now }).status).toBe('no_match');
    expect(index.search({ library: 'firefly', query: '萨姆', allowSpoilers: true, now }).hits[0]?.source.id).toBe('firefly_official_story');
  });
});
