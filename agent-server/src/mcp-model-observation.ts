import type { McpModelCall } from './contracts.ts';
import type { McpToolObservation } from './mcp-http.ts';
import { encodeMcpJson } from './mcp-registry.ts';

export type McpModelObservation =
  | { status: 'completed'; result: McpToolObservation }
  | { status: 'rejected'; reason: 'not_authorized' | 'confirmation_required' | 'declined' | 'catalog_changed' }
  | { status: 'unconfirmed'; reason: 'unavailable' };

/** 宿主边界再次限制回执形状与大小；远端正文只能成为 tool 消息。 */
export function parseMcpModelObservation(call: McpModelCall, value: unknown): McpModelObservation {
  const unconfirmed = { status: 'unconfirmed', reason: 'unavailable' } as const;
  try {
    const raw = encodeMcpJson(value);
    if (new TextEncoder().encode(raw).length > 17000) return unconfirmed;
    const row = JSON.parse(raw);
    if (!row || typeof row !== 'object' || Array.isArray(row) || Object.keys(row).length !== 2) return unconfirmed;
    if (row.status === 'rejected' && ['not_authorized', 'confirmation_required', 'declined', 'catalog_changed'].includes(row.reason))
      return { status: 'rejected', reason: row.reason };
    if (row.status !== 'completed' || !row.result || typeof row.result !== 'object' || Array.isArray(row.result)) return unconfirmed;
    const result = row.result;
    if (new TextEncoder().encode(encodeMcpJson(result)).byteLength > 16384 ||
        Object.keys(result).some(key => !['source', 'serviceId', 'modelName', 'isError', 'content', 'structuredContent', 'truncated'].includes(key)) ||
        ('truncated' in result && result.truncated !== true) ||
        result.source !== 'mcp' || typeof result.serviceId !== 'string' ||
        !/^mcp_[0-9a-f]{32}_[0-9a-z]{1,2}$/.test(call.args.modelName) ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(result.serviceId) ||
        call.args.modelName.split('_')[1] !== result.serviceId.replaceAll('-', '') || result.modelName !== call.args.modelName ||
        typeof result.isError !== 'boolean' || !Array.isArray(result.content) || result.content.some((part: unknown) =>
          !part || typeof part !== 'object' || Array.isArray(part) || Object.keys(part).length !== 2 ||
          !('type' in part) || part.type !== 'text' || !('text' in part) || typeof part.text !== 'string')) return unconfirmed;
    return { status: 'completed', result };
  } catch { return unconfirmed; }
}
