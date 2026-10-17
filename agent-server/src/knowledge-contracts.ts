import { AgentError } from './contracts.ts';

export type KnowledgeRequest = Readonly<{
  version: 'curated_knowledge_v1';
  library: 'health' | 'firefly';
  allowSpoilers: boolean;
}>;
export type KnowledgeCitation = Readonly<{
  citationId: string; title: string; url: string; publishedAt: string | null; checkedAtMillis: number;
}>;
export function hasKnowledge(value: unknown): boolean {
  return value !== null && typeof value === 'object' && Object.hasOwn(value, 'knowledge');
}
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
    Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key)))
    throw new AgentError('invalid_task');
  return value as Record<string, unknown>;
}
export function parseKnowledge(value: unknown): KnowledgeRequest {
  const data = object(value, ['version', 'library', 'allowSpoilers']);
  if (data.version !== 'curated_knowledge_v1' || (data.library !== 'health' && data.library !== 'firefly') ||
    typeof data.allowSpoilers !== 'boolean' || (data.library === 'health' && data.allowSpoilers))
    throw new AgentError('invalid_task');
  return Object.freeze({ version: data.version, library: data.library, allowSpoilers: data.allowSpoilers });
}
export function parseKnowledgeCitations(value: unknown): readonly KnowledgeCitation[] {
  if (!Array.isArray(value) || value.length > 3) throw new AgentError('invalid_task');
  const citations = value.map(item => {
    const row = object(item, ['citationId', 'title', 'url', 'publishedAt', 'checkedAtMillis']);
    if (typeof row.citationId !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(row.citationId) ||
      typeof row.title !== 'string' || !row.title.trim() || row.title.length > 80 ||
      /[\u0000-\u001f\u007f]/.test(row.title) || typeof row.url !== 'string' || row.url.length > 300 ||
      typeof row.checkedAtMillis !== 'number' || !Number.isSafeInteger(row.checkedAtMillis) ||
      row.checkedAtMillis < 0 || row.checkedAtMillis > 8640000000000000)
      throw new AgentError('invalid_task');
    const url = new URL(row.url);
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.search ||
      !['www.who.int', 'hsr.hoyoverse.com'].includes(url.hostname)) throw new AgentError('invalid_task');
    if (row.publishedAt !== null && (typeof row.publishedAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(row.publishedAt) || !Number.isFinite(Date.parse(row.publishedAt)) ||
      new Date(row.publishedAt).toISOString().slice(0, 10) !== row.publishedAt ||
      Date.parse(row.publishedAt) > row.checkedAtMillis)) throw new AgentError('invalid_task');
    return Object.freeze({ citationId: row.citationId, title: row.title, url: row.url,
      publishedAt: row.publishedAt as string | null, checkedAtMillis: row.checkedAtMillis });
  });
  if (new Set(citations.map(row => row.citationId)).size !== citations.length) throw new AgentError('invalid_task');
  return Object.freeze(citations);
}
