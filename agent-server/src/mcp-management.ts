import type { BudgetDatabase } from './d1.ts';
import { createDeviceAuth } from './device-auth.ts';
import { BodyError, errorResponse, readBody } from './agent-http.ts';
import { CloudError, uuid } from './cloud-task-contracts.ts';
import { exactObject } from './tools.ts';
import { createMcpStore, McpStoreError } from './mcp-store.ts';
import type { McpCatalog, McpService } from './mcp-registry.ts';

type Route = { kind: 'list'; after: string | null } | { kind: 'register' } | { kind: 'get'; id: string } | { kind: 'summary'; id: string }
  | { kind: 'endpoint' | 'discover' | 'approve' | 'disable' | 'remove'; id: string };

function route(request: Request): Route {
  const url = new URL(request.url);
  if (url.protocol !== 'https:' || url.hash || url.username || url.password) throw new CloudError('invalid_request');
  const root = '/v1/agent/mcp/services';
  if (url.pathname === root && request.method === 'GET') {
    if ([...url.searchParams.keys()].some(key => key !== 'after') || url.searchParams.getAll('after').length > 1)
      throw new CloudError('invalid_request');
    return { kind: 'list', after: url.searchParams.has('after') ? uuid(url.searchParams.get('after')) : null };
  }
  if (url.search) throw new CloudError('invalid_request');
  if (url.pathname === root) {
    if (request.method !== 'POST') throw new CloudError('invalid_request');
    return { kind: 'register' };
  }
  const match = /^\/v1\/agent\/mcp\/services\/([^/]+)(?:\/(summary|endpoint|discover|approve|disable|remove))?$/.exec(url.pathname);
  if (!match) throw new CloudError('not_found');
  const id = uuid(match[1]);
  const action = match[2] as 'summary' | 'endpoint' | 'discover' | 'approve' | 'disable' | 'remove' | undefined;
  if (request.method !== (!action || action === 'summary' ? 'GET' : 'POST')) throw new CloudError('invalid_request');
  return { kind: action ?? 'get', id };
}

function body(bytes: Uint8Array, kind: Exclude<Route['kind'], 'list' | 'get' | 'summary'>) {
  try {
    const raw: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
    const keys = kind === 'register' ? ['endpoint'] : kind === 'endpoint' ? ['revision', 'endpoint']
      : kind === 'approve' ? ['revision', 'selection', 'confirmed'] : ['revision'];
    const data = exactObject(raw, keys, 'invalid_task');
    if (kind !== 'register') uuid(data.revision);
    if ((kind === 'register' || kind === 'endpoint') && (typeof data.endpoint !== 'string' || data.endpoint.length > 2048))
      throw new CloudError('invalid_request');
    if (kind === 'approve' && data.confirmed !== true) throw new CloudError('invalid_request');
    return data;
  } catch { throw new CloudError('invalid_request'); }
}

const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { 'Cache-Control': 'no-store' } });
// 远端工具定义作为管理数据返回；不返回身份或私密配置，不注入模型上下文。
const view = (service: McpService) => ({ id: service.id, endpoint: service.endpoint, revision: service.revision,
  enabled: service.enabled, catalog: service.catalog, grants: service.grants });

/** discover 只能由可信组装层注入；正式 Worker 暂不配置，无 fetch 默认值或前端回填目录。 */
export async function mcpManagement(request: Request, options: {
  db: BudgetDatabase;
  discover?: (endpoint: string, signal: AbortSignal) => Promise<McpCatalog>;
  phoneDiscovery?: boolean;
}): Promise<Response> {
  try {
    const target = route(request);
    // 先认证再消费正文；设备凭据只用于本服务，绝不转发给 discover。
    const principal = await createDeviceAuth({ db: options.db }).authenticate(request.headers.get('Authorization'));
    if (request.headers.has('content-encoding')) throw new BodyError(415);
    const discover = options.discover;
    const store = createMcpStore({ db: options.db, principal, discover: discover ?? (async () => { throw new CloudError('unavailable'); }) });
    if (target.kind === 'list' || target.kind === 'get' || target.kind === 'summary') {
      await readBody(request, 0);
      if (target.kind === 'get') return json({ service: view(await store.get(target.id)) });
      if (target.kind === 'summary') return json({ service: await store.summary(target.id) });
      const list = await store.list(target.after);
      return json({ services: list.items, nextCursor: list.nextCursor, discoveryAvailable: Boolean(discover),
        ...(options.phoneDiscovery ? { phoneDiscoveryAvailable: true } : {}) });
    }
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? '')) throw new BodyError(415);
    const data = body(await readBody(request, 16384), target.kind);
    if (target.kind === 'register') return json({ service: view(await store.register(data.endpoint as string)) }, 201);
    const revision = uuid(data.revision);
    if (target.kind === 'remove') {
      await store.remove(target.id, revision);
      return json({ removed: true });
    }
    if (target.kind === 'discover' && !discover) throw new CloudError('unavailable');
    const service = target.kind === 'endpoint' ? await store.replaceEndpoint(target.id, revision, data.endpoint as string)
      : target.kind === 'approve' ? await store.approve(target.id, revision, data.selection)
      : target.kind === 'discover' ? await store.discover(target.id, revision, request.signal)
      : await store.disable(target.id, revision);
    return json({ service: view(service) });
  } catch (error) {
    // 拒绝时也释放未读取的上传流，不回显解析正文、SQL 或远端错误。
    void request.body?.cancel().catch(() => {});
    if (error instanceof McpStoreError) return errorResponse(new CloudError(error.code === 'invalid_request' ? 'invalid_request'
      : error.code === 'stale_review' ? 'conflict' : error.code === 'unavailable' ? 'not_found' : 'unavailable'));
    return errorResponse(error);
  }
}
