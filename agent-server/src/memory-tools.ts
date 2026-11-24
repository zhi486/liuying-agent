import type { MemoryToolCall } from './contracts.ts';
import { isSupportedText } from './plain-text.ts';
import { exactObject, MEMORY_UUID } from './tools.ts';

export type MemoryToolObservation =
  | { status: 'found'; items: readonly MemoryItem[] }
  | { status: 'saved'; memory: MemoryItem; replayed: boolean }
  | { status: 'rejected'; reason: string }
  | { status: 'unconfirmed'; reason: 'unavailable' | 'invalid_response' };
type MemoryItem = Readonly<{ id: string; version: number; content: string }>;

const REASONS = new Set(['consent_required', 'scope_denied', 'task_inactive', 'invalid_tool',
  'evidence_required', 'sensitive_content', 'conflict', 'storage_unavailable']);

function item(value: unknown): MemoryItem {
  const row = exactObject(value, ['id', 'version', 'content'], 'invalid_tool');
  if (typeof row.id !== 'string' || !MEMORY_UUID.test(row.id) || typeof row.version !== 'number' ||
      !Number.isInteger(row.version) || row.version < 1 || row.version > 2147483647 ||
      !isSupportedText(row.content) || !row.content.trim() || row.content !== row.content.trim() || row.content.length > 500)
    throw new Error('记忆回执无效');
  return Object.freeze({ id: row.id, version: row.version, content: row.content });
}

/** 校验回执而非猜测执行结果；错误不回显模型参数、用户原文或异常正文。 */
export function parseMemoryObservation(call: MemoryToolCall, value: unknown): MemoryToolObservation {
  try {
    if (value === null || typeof value !== 'object' || !('status' in value)) throw new Error();
    if (value.status === 'rejected') {
      const row = exactObject(value, ['status', 'reason'], 'invalid_tool');
      if (typeof row.reason !== 'string' || !REASONS.has(row.reason)) throw new Error();
      if (row.reason === 'storage_unavailable') return { status: 'unconfirmed', reason: 'unavailable' };
      return { status: 'rejected', reason: row.reason };
    }
    if (value.status === 'unconfirmed') {
      const row = exactObject(value, ['status', 'reason'], 'invalid_tool');
      if (row.reason !== 'unavailable' && row.reason !== 'invalid_response') throw new Error();
      return { status: 'unconfirmed', reason: row.reason };
    }
    if (call.name === 'memory.search') {
      const row = exactObject(value, ['status', 'items'], 'invalid_tool');
      if (row.status !== 'found' || !Array.isArray(row.items) || row.items.length > 5) throw new Error();
      const items = row.items.map(item);
      if (new Set(items.map(row => row.id)).size !== items.length) throw new Error();
      return { status: 'found', items };
    }
    const row = exactObject(value, ['status', 'memory', 'replayed'], 'invalid_tool');
    if (row.status !== 'saved' || typeof row.replayed !== 'boolean') throw new Error();
    const memory = item(row.memory);
    if (memory.content !== call.args.evidence || (call.args.target !== null &&
        (memory.id !== call.args.target.id || memory.version !== call.args.target.version + 1))) throw new Error();
    return { status: 'saved', memory, replayed: row.replayed };
  } catch {
    return { status: 'unconfirmed', reason: 'invalid_response' };
  }
}
