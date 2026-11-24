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
      model: { async generate() { calls++; return { decision: calls === 1
        ? { kind: 'tools', calls: [{ id: 'search', name: 'knowledge.search', args: { query: '流萤' } }] }
        : { kind: 'final', text: '合成回答' }, usage: { inputTokens: 100, outputTokens: 10 } }; } },
      budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
    };
    const result = await runAgent(task, ports, { knowledge: { index, library: 'firefly' } });
    expect(result.status).toBe('completed');
    expect(result.sources.length).toBeGreaterThan(0);
    expect(result.sources.every(source => source.citationId.startsWith('firefly_official_profile'))).toBe(true);
    expect(parseResult(JSON.parse(JSON.stringify(result)))).toEqual(result);
    expect((await runAgent(task, ports)).status).toBe('failed');
    expect(calls).toBe(2);
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
        if (calls === 1) {
          expect(request.messages).toEqual([{ role: 'user', text: '步行算身体活动吗' }]);
          return { decision: { kind: 'tools', calls: [{ id: 'search', name: 'knowledge.search', args: { query: '步行' } }] },
            usage: { inputTokens: 100, outputTokens: 10 } };
        }
        expect(request.messages.at(-1).role).toBe('tool');
        expect(JSON.stringify(request.messages.at(-1).data)).toContain('curated_knowledge_v1');
        return { decision: { kind: 'final', text: '步行属于身体活动。' }, usage: { inputTokens: 100, outputTokens: 10 } };
      } },
      budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} },
    }, { knowledge: { index, library: 'health' } });
    expect(result.status).toBe('completed');
    expect(result.answer).toContain('https://www.who.int/news-room/fact-sheets/detail/physical-activity');
    expect(calls).toBe(2);
    const character = index.search({ library: 'firefly', query: '流萤', allowSpoilers: false, now });
    expect(character.status).toBe('matched');
    expect(character.hits.every(hit => !hit.source.spoiler)).toBe(true);
    expect(character.excluded.spoiler).toBe(1);
    expect(index.search({ library: 'firefly', query: '萨姆', allowSpoilers: false, now }).status).toBe('no_match');
    expect(index.search({ library: 'firefly', query: '萨姆', allowSpoilers: true, now }).hits[0]?.source.id).toBe('firefly_official_story');
  });
  it('闲聊零检索，种子到期后不能提供来源或正文', async () => {
    const index = createKnowledgeIndex(CURATED_KNOWLEDGE);
    const now = Math.max(...CURATED_KNOWLEDGE.map(doc => doc.expiresAtMillis));
    expect(index.search({ library: 'both', query: '流萤步行', allowSpoilers: true, now }).status).toBe('unavailable');
    let searches = 0; let calls = 0;
    const task = { taskId: 'runtime_no_search', prompt: '你好', consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
      knowledge: { version: 'curated_knowledge_v1', library: 'both', allowSpoilers: false } };
    const ports = { model: { async generate() { calls++; return { decision: { kind: 'final', text: '你好' },
      usage: { inputTokens: 100, outputTokens: 10 } }; } },
      budget: { async reserve(request) { return { reservationId: request.requestId }; }, async settle() {} } };
    const result = await runAgent(task, ports, { knowledge: { library: 'both', index: { search(request) {
      searches++; return index.search(request);
    } } } });
    expect(result.status).toBe('completed'); expect(result.sources).toEqual([]);
    expect(searches).toBe(0); expect(calls).toBe(1);
    calls = 0;
    ports.model.generate = async request => {
      calls++;
      if (calls === 1) return { decision: { kind: 'tools', calls: [
        { id: 'expired_search', name: 'knowledge.search', args: { query: '流萤步行' } },
      ] }, usage: { inputTokens: 100, outputTokens: 10 } };
      const observation = request.messages.at(-1).data.curated_knowledge_v1;
      expect(observation.status).toBe('unavailable'); expect(observation.hits).toEqual([]);
      return { decision: { kind: 'final', text: '当前资料不可用。' }, usage: { inputTokens: 100, outputTokens: 10 } };
    };
    const expired = await runAgent(task, ports, { knowledge: { library: 'both', index: { search(request) {
      searches++; return index.search({ ...request, now });
    } } } });
    expect(expired.status).toBe('completed'); expect(expired.sources).toEqual([]);
    expect(searches).toBe(1); expect(calls).toBe(2);
  });
});
