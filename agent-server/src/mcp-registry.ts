/** MCP 本地授权核心；不是网络客户端，也不接受模型或前端直接提供的权威状态。 */
type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
type JsonObject = { readonly [key: string]: Json };
type Tool = Readonly<{ name: string; definition: JsonObject }>;
export type McpCatalog = Readonly<{ fingerprint: string; tools: readonly Tool[] }>;
type Grant = Readonly<{ name: string; modelName: string; mode: 'read' | 'confirm' }>;
export type McpService = Readonly<{
  id: string; ownerId: string; endpoint: string; revision: string;
  enabled: boolean; catalog: McpCatalog | null; grants: readonly Grant[];
}>;
type PolicyCode = 'invalid_endpoint' | 'invalid_catalog' | 'discovery_failed' | 'cancelled' |
  'scope_denied' | 'stale_review' | 'catalog_required' | 'invalid_grant';

class McpPolicyError extends Error {
  readonly code: PolicyCode;
  constructor(code: PolicyCode) {
    super(code === 'discovery_failed' ? 'MCP 工具发现失败' : 'MCP 配置或授权不满足要求');
    this.code = code;
  }
}
const fail = (code: PolicyCode): never => { throw new McpPolicyError(code); };
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const MAX_BYTES = 131072;

/** 只接收有界 JSON 数据；此处不是 JSON Schema 语义或调用参数校验器。 */
function canonical(value: unknown): string {
  let nodes = 0;
  let characters = 0;
  const visit = (item: unknown, depth: number): string => {
    if (++nodes > 8192 || depth > 16) return fail('invalid_catalog');
    let text: string;
    if (item === null || typeof item === 'boolean' || typeof item === 'string' ||
        (typeof item === 'number' && Number.isFinite(item))) {
      if (typeof item === 'string' && item.length > MAX_BYTES) return fail('invalid_catalog');
      text = JSON.stringify(item);
      characters += text.length;
    } else if (Array.isArray(item)) {
      text = `[${item.map(child => visit(child, depth + 1)).join(',')}]`;
      characters += item.length + 2;
    } else if (object(item) && (Object.getPrototypeOf(item) === Object.prototype || Object.getPrototypeOf(item) === null)) {
      const keys = Object.keys(item).sort();
      text = `{${keys.map(key => {
        characters += key.length;
        return `${JSON.stringify(key)}:${visit(item[key], depth + 1)}`;
      }).join(',')}}`;
      characters += keys.length * 4 + 2;
    } else return fail('invalid_catalog');
    if (characters > MAX_BYTES) return fail('invalid_catalog');
    return text;
  };
  const text = visit(value, 0);
  if (new TextEncoder().encode(text).byteLength > MAX_BYTES) return fail('invalid_catalog');
  return text;
}

/** 供工具调用生成不可变 JSON 快照；仍须按具体通路检查更小的字节上限。 */
export { canonical as encodeMcpJson };

function freezeJson(value: Json): Json {
  if (value !== null && typeof value === 'object') {
    for (const child of Object.values(value)) freezeJson(child);
    Object.freeze(value);
  }
  return value;
}

/** 全部分页成功才返回快照。传输层仍必须限制原始响应、时限及重定向。 */
export async function collectMcpCatalog(
  listPage: (cursor: string | undefined, signal: AbortSignal) => Promise<unknown>,
  signal: AbortSignal,
): Promise<McpCatalog> {
  let cursor: string | undefined;
  let bytes = 0;
  const cursors = new Set<string>();
  const tools = new Map<string, Tool>();
  for (let pageNumber = 0; pageNumber < 8; pageNumber++) {
    if (signal.aborted) return fail('cancelled');
    let raw: unknown;
    try { raw = await listPage(cursor, signal); }
    catch { return fail(signal.aborted ? 'cancelled' : 'discovery_failed'); }
    if (signal.aborted) return fail('cancelled');
    const text = canonical(raw);
    bytes += new TextEncoder().encode(text).byteLength;
    if (bytes > MAX_BYTES) return fail('invalid_catalog');
    const page: unknown = JSON.parse(text);
    if (!object(page) || !Array.isArray(page.tools)) return fail('invalid_catalog');
    for (const definition of page.tools) {
      if (!object(definition) || typeof definition.name !== 'string' ||
          !/^[A-Za-z0-9_.-]{1,128}$/.test(definition.name) || tools.has(definition.name) ||
          !object(definition.inputSchema) || definition.inputSchema.type !== 'object' ||
          ('outputSchema' in definition && (!object(definition.outputSchema) || definition.outputSchema.type !== 'object')) ||
          ('description' in definition && typeof definition.description !== 'string') ||
          ('title' in definition && typeof definition.title !== 'string') ||
          ('annotations' in definition && !object(definition.annotations))) return fail('invalid_catalog');
      if (tools.size >= 64) return fail('invalid_catalog');
      // 来自上方有界 JSON 的副本；保留所有字段参与指纹，未知字段变化也保守地撤权。
      tools.set(definition.name, Object.freeze({ name: definition.name, definition: freezeJson(definition as JsonObject) as JsonObject }));
    }
    if (!('nextCursor' in page)) {
      const sorted = [...tools.values()].sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical(sorted.map(row => row.definition))));
      if (signal.aborted) return fail('cancelled');
      const fingerprint = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
      return Object.freeze({ fingerprint, tools: Object.freeze(sorted) });
    }
    if (typeof page.nextCursor !== 'string' || !page.nextCursor || page.nextCursor.length > 2048 || cursors.has(page.nextCursor))
      return fail('invalid_catalog');
    cursor = page.nextCursor;
    cursors.add(cursor);
  }
  return fail('invalid_catalog');
}

/** 仅做登记格式检查；域名的 DNS 解析与连接固定必须由未来传输层另行保证。 */
export function normalizeMcpEndpoint(raw: string): string {
  try {
    if (typeof raw !== 'string' || raw.length > 2048 || raw !== raw.trim() || /[?#\\\s]/.test(raw)) return fail('invalid_endpoint');
    const url = new URL(raw);
    if (url.protocol !== 'https:' || url.username || url.password || url.port ||
        !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/i.test(url.hostname) ||
        /\.(?:localhost|local|internal|lan|home|onion)$/i.test(url.hostname)) return fail('invalid_endpoint');
    return url.href;
  } catch { return fail('invalid_endpoint'); }
}

function checkOwner(service: McpService, actor: string): void {
  if (!actor || actor !== service.ownerId) fail('scope_denied');
}

function reset(service: McpService, changes: Partial<McpService> = {}): McpService {
  return Object.freeze({ ...service, ...changes, revision: crypto.randomUUID(), enabled: false, grants: Object.freeze([]) });
}

export function createMcpService(ownerId: string, endpoint: string): McpService {
  if (!ownerId || ownerId.length > 128) return fail('scope_denied');
  return Object.freeze({ id: crypto.randomUUID(), ownerId, endpoint: normalizeMcpEndpoint(endpoint),
    revision: crypto.randomUUID(), enabled: false, catalog: null, grants: Object.freeze([]) });
}

export function replaceMcpEndpoint(service: McpService, actor: string, endpoint: string): McpService {
  checkOwner(service, actor);
  const normalized = normalizeMcpEndpoint(endpoint);
  return normalized === service.endpoint ? service : reset(service, { endpoint: normalized, catalog: null });
}

export function setMcpCatalog(service: McpService, actor: string, catalog: McpCatalog): McpService {
  checkOwner(service, actor);
  return catalog.fingerprint === service.catalog?.fingerprint ? service : reset(service, { catalog });
}

export function invalidateMcpCatalog(service: McpService, actor: string): McpService {
  checkOwner(service, actor);
  return reset(service, { catalog: null });
}

export function disableMcpService(service: McpService, actor: string): McpService {
  checkOwner(service, actor);
  return reset(service);
}

/** 只由用户确认后的管理通路调用；模式来自用户选择而非服务 annotations。 */
export function approveMcpTools(service: McpService, actor: string, reviewedRevision: string, selection: unknown): McpService {
  checkOwner(service, actor);
  if (reviewedRevision !== service.revision) return fail('stale_review');
  if (!service.catalog) return fail('catalog_required');
  if (!Array.isArray(selection) || selection.length === 0 || selection.length > 64) return fail('invalid_grant');
  const names = new Set<string>();
  const grants: Grant[] = selection.map(row => {
    if (!object(row) || Object.keys(row).length !== 2 || typeof row.name !== 'string' ||
        (row.mode !== 'read' && row.mode !== 'confirm') || names.has(row.name)) return fail('invalid_grant');
    const index = service.catalog!.tools.findIndex(tool => tool.name === row.name);
    if (index < 0) return fail('invalid_grant');
    names.add(row.name);
    return Object.freeze({ name: row.name, mode: row.mode,
      modelName: `mcp_${service.id.replaceAll('-', '')}_${index.toString(36)}` });
  });
  return Object.freeze({ ...service, revision: crypto.randomUUID(), enabled: true, grants: Object.freeze(grants) });
}

export type McpAuthorization =
  | Readonly<{ status: 'allowed_read' | 'confirmation_required'; name: string }>
  | Readonly<{ status: 'rejected'; reason: 'scope_denied' | 'stale_review' | 'disabled' | 'tool_not_granted' }>;

/** 调用前读取最新权威状态再检查；返回允许也不代表 schema 已验证或网络可安全连接。 */
export function authorizeMcpTool(service: McpService, actor: string, revision: string, modelName: string): McpAuthorization {
  if (!actor || actor !== service.ownerId) return { status: 'rejected', reason: 'scope_denied' };
  if (revision !== service.revision) return { status: 'rejected', reason: 'stale_review' };
  if (!service.enabled || !service.catalog) return { status: 'rejected', reason: 'disabled' };
  const grant = service.grants.find(row => row.modelName === modelName);
  if (!grant) return { status: 'rejected', reason: 'tool_not_granted' };
  return { status: grant.mode === 'read' ? 'allowed_read' : 'confirmation_required', name: grant.name };
}
