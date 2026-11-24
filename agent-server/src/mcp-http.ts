import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/client/validators/cf-worker';
import { authorizeMcpTool, collectMcpCatalog, encodeMcpJson, normalizeMcpEndpoint, type McpCatalog, type McpService } from './mcp-registry.ts';
import { mcpSchemaValidator } from './mcp-schema.ts';

type FailureCode = 'invalid_options' | 'cancelled' | 'timeout' | 'transport_failed' |
  'invalid_response' | 'catalog_changed' | 'limit_exceeded' | 'authentication_required' | 'authorization_denied';
type FailurePhase = 'validation' | 'initialize' | 'catalog' | 'call' | 'cleanup';
class McpHttpError extends Error {
  readonly code: FailureCode;
  readonly phase: FailurePhase;
  constructor(code: FailureCode, phase: FailurePhase = 'validation') {
    super('MCP 服务连接未完成'); this.code = code; this.phase = phase;
  }
}
type Options = Readonly<{
  endpoint: string;
  /** 必须由可信宿主提供受限出网；不得直接接全局 fetch，此处不保证 DNS／连接地址安全。 */
  send: (request: Request) => Promise<Response>;
  signal: AbortSignal;
  timeoutMs?: number;
}>;

type Session = Readonly<{ client: Client; signal: AbortSignal; timeoutMs: number; check: () => void }>;
type Dispatch = Readonly<{ name: string; argumentsJson: string; authorize: (signal: AbortSignal) => Promise<void>; sent: () => void }>;

/** 发现与调用共用同一受限会话；调用模式仍只放行一次完全匹配的 tools/call。 */
async function withMcpSession<T>(options: Options, run: (session: Session, catalog: McpCatalog) => Promise<T>, dispatch?: Dispatch): Promise<T> {
  const endpoint = normalizeMcpEndpoint(options.endpoint);
  const timeoutMs = options.timeoutMs ?? (dispatch ? 25000 : 10000);
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 30000 || typeof options.send !== 'function')
    throw new McpHttpError('invalid_options');
  let phase: FailurePhase = 'initialize';
  const controller = new AbortController();
  const expires = performance.now() + timeoutMs;
  const stop = (code: FailureCode) => controller.abort(new McpHttpError(code));
  const cancel = () => stop('cancelled');
  options.signal.addEventListener('abort', cancel, { once: true });
  if (options.signal.aborted) cancel();
  const timer = setTimeout(() => stop('timeout'), timeoutMs);
  const check = () => {
    if (performance.now() >= expires && !controller.signal.aborted) stop('timeout');
    if (controller.signal.aborted) throw controller.signal.reason;
  };
  const wait = async <T>(pending: Promise<T>): Promise<T> => new Promise<T>((resolve, reject) => {
    const abort = () => reject(controller.signal.reason);
    controller.signal.addEventListener('abort', abort, { once: true });
    // 即使先取消，也消费迟到的异常；底层 send 同样必须支持 Request.signal。
    pending.then(resolve, reject).finally(() => controller.signal.removeEventListener('abort', abort));
    try { check(); } catch (error) { controller.signal.removeEventListener('abort', abort); reject(error); }
  });
  let requestCount = 0;
  let totalBytes = 0;
  let changed = false;
  let dispatched = false;
  const readers = new Set<ReadableStreamDefaultReader<Uint8Array>>();
  const client = new Client({ name: 'liuying-agent', version: '0.1.0' }, {
    capabilities: {}, versionNegotiation: { mode: 'legacy' }, inputRequired: { autoFulfill: false },
    jsonSchemaValidator: new CfWorkerJsonSchemaValidator(), enforceStrictCapabilities: true,
  });
  client.onerror = error => { if (!controller.signal.aborted) stop(error instanceof McpHttpError ? error.code : 'transport_failed'); };
  client.setNotificationHandler('notifications/tools/list_changed', () => { changed = true; stop('catalog_changed'); });
  const transport = new StreamableHTTPClientTransport(new URL(endpoint), {
    requestInit: { redirect: 'manual', cache: 'no-store' },
    onInsufficientScope: 'throw', maxStepUpRetries: 0,
    reconnectionOptions: { maxRetries: 0, initialReconnectionDelay: 1000, maxReconnectionDelay: 1000, reconnectionDelayGrowFactor: 1 },
    fetch: async (url, init) => {
      check();
      if (new URL(url).href !== endpoint || ++requestCount > (dispatch ? 13 : 12)) throw new McpHttpError('limit_exceeded');
      const signal = init?.signal ? AbortSignal.any([controller.signal, init.signal]) : controller.signal;
      // Workers 不支持 redirect:error；手动模式只接收首个响应，下方拒绝全部 3xx。
      const request = new Request(url, { ...init, signal, redirect: 'manual', cache: 'no-store' });
      if (!['GET', 'POST', 'DELETE'].includes(request.method)) throw new McpHttpError('invalid_options');
      let isCall = false;
      if (request.method === 'POST') {
        const body = await request.clone().text();
        if (new TextEncoder().encode(body).byteLength > (dispatch ? 16384 : 8192)) throw new McpHttpError('limit_exceeded');
        const message: unknown = JSON.parse(body);
        isCall = !!dispatch && !!message && typeof message === 'object' && 'method' in message && message.method === 'tools/call';
        if (!message || typeof message !== 'object' || Array.isArray(message) ||
            ('method' in message && !isCall && !['initialize', 'notifications/initialized', 'tools/list', 'notifications/cancelled']
              .includes(String(message.method)))) throw new McpHttpError('invalid_options');
        if (isCall && dispatch) {
          const params = 'params' in message ? message.params : null;
          if (dispatched || !params || typeof params !== 'object' || Array.isArray(params) ||
              Object.keys(params).length !== 2 || !('name' in params) || params.name !== dispatch.name ||
              !('arguments' in params) || encodeMcpJson(params.arguments) !== dispatch.argumentsJson)
            throw new McpHttpError('invalid_options');
          try {
            if (await wait(dispatch.authorize(controller.signal)) !== undefined) throw new McpHttpError('authorization_denied');
          }
          catch { check(); throw new McpHttpError('authorization_denied'); }
        }
      }
      check();
      // 保守标记：一旦交给宿主，后续失败都不能宣称没有外部副作用或安全地重发。
      if (isCall && dispatch) { dispatched = true; dispatch.sent(); }
      const pending = options.send(request).then(async response => {
        if (signal.aborted) { await response.body?.cancel().catch(() => {}); check(); }
        return response;
      });
      const response = await wait(pending);
      check();
      const session = response.headers.get('mcp-session-id');
      if (response.redirected || (response.url && response.url !== endpoint) ||
          (session !== null && !/^[\x21-\x7e]{1,256}$/.test(session))) {
        await response.body?.cancel().catch(() => {});
        throw new McpHttpError('invalid_response');
      }
      if (!response.ok && !(request.method === 'GET' && response.status === 405) &&
          !(request.method === 'DELETE' && response.status === 405)) {
        await response.body?.cancel().catch(() => {});
        throw new McpHttpError(response.status === 401 || response.status === 403 ? 'authentication_required' : 'transport_failed');
      }
      const type = response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase();
      if (response.status === 200 && type !== 'application/json' && type !== 'text/event-stream') {
        await response.body?.cancel().catch(() => {});
        throw new McpHttpError('invalid_response');
      }
      let bytes = 0;
      const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false });
      if (!response.body) return new Response(null, { status: response.status, headers: response.headers });
      const reader = response.body.getReader();
      readers.add(reader);
      // 显式取消 pending read；不能依赖 workerd 的 pipeTo(signal) 唤醒无数据 SSE。
      const abort = () => { void reader.cancel().catch(() => {}); };
      signal.addEventListener('abort', abort, { once: true });
      const finish = () => { signal.removeEventListener('abort', abort); readers.delete(reader); };
      const stream = new ReadableStream<Uint8Array>({
        async pull(output) {
          try {
            const chunk = await reader.read();
            if (chunk.done) { decoder.decode(); output.close(); finish(); return; }
            check();
            bytes += chunk.value.byteLength;
            totalBytes += chunk.value.byteLength;
            if (bytes > 131072 || totalBytes > 262144) throw new McpHttpError('limit_exceeded');
            decoder.decode(chunk.value, { stream: true });
            output.enqueue(chunk.value);
          } catch (error) {
            if (!controller.signal.aborted) stop(error instanceof McpHttpError ? error.code : 'invalid_response');
            output.error(controller.signal.reason);
            await reader.cancel().catch(() => {});
            finish();
          }
        },
        async cancel() { await reader.cancel().catch(() => {}); finish(); },
      });
      return new Response(stream, { status: response.status, headers: response.headers });
    },
  });
  let result: T;
  try {
    check();
    await wait(client.connect(transport, { signal: controller.signal, timeout: timeoutMs, resetTimeoutOnProgress: false }));
    if (!client.getServerCapabilities()?.tools) throw new McpHttpError('invalid_response');
    phase = 'catalog';
    const catalog = await wait(collectMcpCatalog((cursor, signal) => client.request({ method: 'tools/list',
      params: cursor === undefined ? {} : { cursor } }, { signal, timeout: timeoutMs, resetTimeoutOnProgress: false }), controller.signal));
    check();
    if (dispatch) phase = 'call';
    result = await wait(run({ client, signal: controller.signal, timeoutMs, check }, catalog));
    check();
    phase = 'cleanup';
    await wait(transport.terminateSession());
  } catch (error) {
    if (controller.signal.aborted && controller.signal.reason instanceof McpHttpError)
      throw new McpHttpError(controller.signal.reason.code, phase);
    if (error instanceof McpCallError) throw error;
    throw new McpHttpError(error instanceof McpHttpError ? error.code : 'transport_failed', phase);
  } finally {
    controller.abort();
    await client.close().catch(() => {});
    await Promise.all([...readers].map(reader => reader.cancel().catch(() => {})));
    clearTimeout(timer);
    options.signal.removeEventListener('abort', cancel);
  }
  if (options.signal.aborted) throw new McpHttpError('cancelled', phase);
  if (performance.now() >= expires) throw new McpHttpError('timeout', phase);
  if (changed) throw new McpHttpError('catalog_changed', phase);
  return result;
}

/** 仅发现目录，不调用工具、执行远端 instructions、读取资源或申请额外权限。 */
export function discoverMcpHttp(options: Options): Promise<McpCatalog> {
  return withMcpSession(options, async (_session, catalog) => catalog);
}

export type McpDispatch = Readonly<{
  serviceId: string; ownerId: string; endpoint: string; revision: string; fingerprint: string;
  modelName: string; name: string; mode: 'read' | 'confirm'; argumentsJson: string;
}>;
export type McpToolObservation = Readonly<{
  source: 'mcp'; serviceId: string; modelName: string; isError: boolean;
  content: readonly Readonly<{ type: 'text'; text: string }>[];
  structuredContent?: unknown;
  truncated?: true;
}>;

/** 只缩减已校验的观察，不放宽网络上限；片段标记由宿主生成。 */
function boundObservation(observation: McpToolObservation): McpToolObservation {
  const size = (value: McpToolObservation) => new TextEncoder().encode(encodeMcpJson(value)).byteLength;
  if (size(observation) <= 16384) return JSON.parse(encodeMcpJson(observation)) as McpToolObservation;
  const content: { type: 'text'; text: string }[] = [];
  const excerpt: McpToolObservation = { source: 'mcp', serviceId: observation.serviceId, modelName: observation.modelName,
    isError: observation.isError, truncated: true, content };
  // 不截断结构化 JSON；大结果统一只保留按原顺序排列的文本前缀。
  for (const part of observation.content) {
    const row = { type: 'text' as const, text: part.text };
    content.push(row);
    if (size(excerpt) <= 16384) continue;
    row.text = '';
    if (size(excerpt) > 16384) { content.pop(); break; }
    const points = Array.from(part.text);
    let low = 0;
    let high = points.length;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      row.text = points.slice(0, middle).join('');
      if (size(excerpt) <= 16384) low = middle;
      else high = middle - 1;
    }
    row.text = points.slice(0, low).join('');
    break;
  }
  return JSON.parse(encodeMcpJson(excerpt)) as McpToolObservation;
}
type CallOptions = Omit<Options, 'endpoint'> & Readonly<{
  service: McpService; actor: string; revision: string; modelName: string; arguments: unknown;
  /** 可信宿主须重新读取权威状态，并原子消费绑定这些参数的单次许可；不得从模型确认字段生成。缺省不放行。 */
  authorizeDispatch: (dispatch: McpDispatch, signal: AbortSignal) => Promise<void>;
}>;
type CallFailureCode = FailureCode | 'invalid_arguments' | 'unsupported_schema' | 'unsupported_result' | 'unsupported_tool' |
  'scope_denied' | 'stale_review' | 'disabled' | 'tool_not_granted';
export class McpCallError extends Error {
  readonly code: CallFailureCode;
  readonly dispatchState: 'not_sent' | 'sent';
  readonly phase: FailurePhase;
  constructor(code: CallFailureCode, sent: boolean, phase: FailurePhase = 'validation') {
    super('MCP 工具调用未完成'); this.code = code; this.dispatchState = sent ? 'sent' : 'not_sent'; this.phase = phase;
  }
}

/** 底层执行器，尚未接正式路由；不实现或冒充用户确认、持久幂等、可信出网及模型工具注册。 */
export async function callMcpHttp(options: CallOptions): Promise<McpToolObservation> {
  let sent = false;
  const fail = (code: CallFailureCode, phase: FailurePhase = 'validation'): never => { throw new McpCallError(code, sent, phase); };
  try {
    if (typeof options.authorizeDispatch !== 'function') return fail('invalid_options');
    const authorization = authorizeMcpTool(options.service, options.actor, options.revision, options.modelName);
    if (authorization.status === 'rejected') return fail(authorization.reason);
    const tool = options.service.catalog!.tools.find(row => row.name === authorization.name)!;
    const execution = tool.definition.execution;
    if (execution && typeof execution === 'object' && !Array.isArray(execution) &&
        'taskSupport' in execution && execution.taskSupport === 'required') return fail('unsupported_tool');
    let validateInput: (value: unknown) => boolean;
    let validateOutput: ((value: unknown) => boolean) | undefined;
    try {
      validateInput = mcpSchemaValidator(tool.definition.inputSchema);
      if ('outputSchema' in tool.definition) validateOutput = mcpSchemaValidator(tool.definition.outputSchema);
    } catch { return fail('unsupported_schema'); }
    let argumentsJson: string;
    try {
      argumentsJson = encodeMcpJson(options.arguments);
      if (new TextEncoder().encode(argumentsJson).byteLength > 8192 || !validateInput(JSON.parse(argumentsJson))) return fail('invalid_arguments');
    } catch { return fail('invalid_arguments'); }
    const dispatch: McpDispatch = Object.freeze({ serviceId: options.service.id, ownerId: options.actor,
      endpoint: normalizeMcpEndpoint(options.service.endpoint), revision: options.revision, fingerprint: options.service.catalog!.fingerprint,
      modelName: options.modelName, name: authorization.name, mode: authorization.status === 'allowed_read' ? 'read' : 'confirm', argumentsJson });
    const authorize = options.authorizeDispatch;
    return await withMcpSession({ ...options, endpoint: dispatch.endpoint }, async ({ client, signal, timeoutMs, check }, catalog) => {
      if (catalog.fingerprint !== dispatch.fingerprint) return fail('catalog_changed', 'catalog');
      // 使用官方低层 request，避免 callTool 的隐式目录缓存、扩展请求头或兼容重发。
      const result = await client.request({ method: 'tools/call', params: { name: dispatch.name, arguments: JSON.parse(argumentsJson) } },
        { signal, timeout: timeoutMs, resetTimeoutOnProgress: false });
      check();
      if (result.content.some(row => row.type !== 'text')) return fail('unsupported_result', 'call');
      try {
        if (!result.isError && validateOutput && (result.structuredContent === undefined || !validateOutput(result.structuredContent))) return fail('invalid_response', 'call');
      } catch { return fail('invalid_response', 'call'); }
      const observation = { source: 'mcp' as const, serviceId: dispatch.serviceId, modelName: dispatch.modelName,
        isError: result.isError === true, content: result.content.map(row => ({ type: 'text' as const, text: row.type === 'text' ? row.text : '' })),
        ...(result.structuredContent === undefined ? {} : { structuredContent: result.structuredContent }) };
      // JSON 副本不会携带 SDK／远端对象原型和 _meta；内容只作为观察，不能提升为指令。
      return boundObservation(observation);
    }, { name: dispatch.name, argumentsJson, authorize: signal => authorize(dispatch, signal), sent: () => { sent = true; } });
  } catch (error) {
    if (error instanceof McpCallError) throw error;
    return fail(error instanceof McpHttpError ? error.code : 'transport_failed',
      error instanceof McpHttpError ? error.phase : 'validation');
  }
}
