import { CloudError, uuid } from './cloud-task-contracts.ts';
import { BodyError, readBody } from './agent-http.ts';
import { parseNativeMcpReply } from './native-mcp-channel.ts';
import { normalizeMcpEndpoint } from './mcp-registry.ts';

export const MCP_DISCOVERY_TIMEOUT_MS = 25_000;
const MCP_DISCOVERY_DEADLINES_MS = new Set([10_000, MCP_DISCOVERY_TIMEOUT_MS]);

export type McpDiscoveryInput = Readonly<{ requestId: string; serviceId: string; revision: string; generation: string }>;
export type McpDiscoveryView = Readonly<McpDiscoveryInput & {
  version: 'mcp_discovery_v1'; endpoint: string; startedAtMillis: number; deadlineAtMillis: number;
  status: 'running' | 'completed' | 'unconfirmed'; resultRevision: string | null;
  reason: null | 'unavailable' | 'service_changed' | 'interrupted' | 'timed_out';
}>;
export type McpDiscoveryReply = { ok: true; discovery: McpDiscoveryView } | { ok: false; error: import('./cloud-task-contracts.ts').CloudErrorCode };
export interface McpDiscoveryReceipt { accepted(discovery: McpDiscoveryView): Promise<void> }

function exact(input: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).length !== keys.length ||
      keys.some(key => !Object.hasOwn(input, key))) throw new CloudError('invalid_request');
  return input as Record<string, unknown>;
}
export function parseMcpDiscoveryInput(input: unknown): McpDiscoveryInput {
  const row = exact(input, ['requestId', 'serviceId', 'revision', 'generation']);
  return Object.freeze({ requestId: uuid(row.requestId), serviceId: uuid(row.serviceId),
    revision: uuid(row.revision), generation: uuid(row.generation) });
}

/** 持久记录同样严格重建；缺少或损坏的元数据不转为新的执行许可。 */
export function parseMcpDiscoveryView(input: unknown): McpDiscoveryView {
  try {
    const row = exact(input, ['version', 'requestId', 'serviceId', 'revision', 'generation', 'endpoint',
      'startedAtMillis', 'deadlineAtMillis', 'status', 'resultRevision', 'reason']);
    const scope = parseMcpDiscoveryInput({ requestId: row.requestId, serviceId: row.serviceId, revision: row.revision, generation: row.generation });
    if (row.version !== 'mcp_discovery_v1' || typeof row.endpoint !== 'string' || normalizeMcpEndpoint(row.endpoint) !== row.endpoint ||
        typeof row.startedAtMillis !== 'number' || !Number.isSafeInteger(row.startedAtMillis) || row.startedAtMillis < 0 ||
        typeof row.deadlineAtMillis !== 'number' || !Number.isSafeInteger(row.deadlineAtMillis) ||
        !MCP_DISCOVERY_DEADLINES_MS.has(row.deadlineAtMillis - row.startedAtMillis) ||
        !['running', 'completed', 'unconfirmed'].includes(String(row.status)))
      throw new CloudError('invalid_request');
    if (row.status === 'completed') { uuid(row.resultRevision); if (row.reason !== null) throw new CloudError('invalid_request'); }
    else if (row.resultRevision !== null || (row.status === 'running' ? row.reason !== null :
      !['unavailable', 'service_changed', 'interrupted', 'timed_out'].includes(String(row.reason)))) throw new CloudError('invalid_request');
    return Object.freeze({ ...scope, version: 'mcp_discovery_v1', endpoint: row.endpoint, startedAtMillis: row.startedAtMillis,
      deadlineAtMillis: row.deadlineAtMillis, status: row.status as McpDiscoveryView['status'],
      resultRevision: row.resultRevision as string | null, reason: row.reason as McpDiscoveryView['reason'] });
  } catch { throw new CloudError('unavailable'); }
}

export type McpDiscoveryRoute = { kind: 'start' } | { kind: 'get' | 'take' | 'reply'; id: string };
export function mcpDiscoveryRoute(request: Request): McpDiscoveryRoute {
  const url = new URL(request.url);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) throw new CloudError('invalid_request');
  if (url.pathname === '/v1/agent/mcp/discoveries') {
    if (request.method !== 'POST') throw new CloudError('invalid_request');
    return { kind: 'start' };
  }
  const match = /^\/v1\/agent\/mcp\/discoveries\/([^/]+)(?:\/(take|reply))?$/.exec(url.pathname);
  if (!match) throw new CloudError('not_found');
  const kind = (match[2] ?? 'get') as 'get' | 'take' | 'reply';
  if (request.method !== (kind === 'get' ? 'GET' : 'POST')) throw new CloudError('invalid_request');
  return { kind, id: uuid(match[1]) };
}

/** 须在设备认证后调用；回执正文的 JSON 转义外壳与内部 128 KiB 上限分别校验。 */
export async function readMcpDiscoveryBody(request: Request, route: McpDiscoveryRoute): Promise<unknown> {
  if (request.headers.has('content-encoding')) throw new BodyError(415);
  if (route.kind === 'get' || route.kind === 'take') { await readBody(request, 0); return undefined; }
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? '')) throw new BodyError(415);
  const bytes = await readBody(request, route.kind === 'start' ? 4096 : 1048576);
  try {
    const input: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
    return route.kind === 'start' ? parseMcpDiscoveryInput(input) : parseNativeMcpReply(input);
  } catch { throw new CloudError('invalid_request'); }
}
