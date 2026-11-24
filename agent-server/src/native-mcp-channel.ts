import type { Principal } from './device-auth.ts';
import { digest } from './device-auth.ts';
import { uuid } from './cloud-task-contracts.ts';
import { readBody } from './agent-http.ts';
import { normalizeMcpEndpoint } from './mcp-registry.ts';

type Code = 'invalid_request' | 'scope_denied' | 'inactive' | 'conflict' | 'limit_exceeded' | 'transport_failed';
export class NativeMcpError extends Error {
  readonly code: Code;
  constructor(code: Code) { super('手机插件通道未完成'); this.code = code; }
}
const fail = (code: Code): never => { throw new NativeMcpError(code); };
const bytes = (text: string) => new TextEncoder().encode(text).byteLength;
const session = (value: unknown): value is string | null => value === null ||
  (typeof value === 'string' && /^[\x21-\x7e]{1,256}$/.test(value));
type Binding = Readonly<Principal & {
  requestId: string; taskId: string; serviceId: string; revision: string; generation: string; endpoint: string;
}>;
export type NativeMcpRequest = Readonly<Omit<Binding, 'ownerId' | 'deviceId'> & {
  version: 'native_mcp_v1'; exchangeId: string; method: 'POST' | 'DELETE'; body: string | null;
  sessionId: string | null; protocolVersion: string | null;
}>;
const failures = ['credential_unavailable', 'stale_configuration', 'transport_failed', 'invalid_response',
  'catalog_changed', 'limit_exceeded', 'authentication_required', 'cancelled'] as const;
type Reply = Readonly<{ version: 'native_mcp_v1'; exchangeId: string; generation: string } & (
  { error: typeof failures[number] } | { status: 200 | 202 | 204 | 405; text: string; sessionId: string | null }
)>;

/** 只解析原生固定回执，不接受任意上游响应头、URL、错误正文或身份声明。 */
export function parseNativeMcpReply(input: unknown): Reply {
  try {
    if (!input || typeof input !== 'object' || Array.isArray(input)) return fail('invalid_request');
    const row = input as Record<string, unknown>;
    const keys = 'error' in row ? ['version', 'exchangeId', 'generation', 'error']
      : ['version', 'exchangeId', 'generation', 'status', 'text', 'sessionId'];
    if (Object.keys(row).length !== keys.length || Object.keys(row).some(key => !keys.includes(key)) || row.version !== 'native_mcp_v1')
      return fail('invalid_request');
    const base = { version: 'native_mcp_v1' as const, exchangeId: uuid(row.exchangeId), generation: uuid(row.generation) };
    if ('error' in row) {
      if (!failures.includes(row.error as typeof failures[number])) return fail('invalid_request');
      return Object.freeze({ ...base, error: row.error as typeof failures[number] });
    }
    if (![200, 202, 204, 405].includes(row.status as number) || typeof row.text !== 'string' ||
        row.text.length > 131072 || bytes(row.text) > 131072 || !session(row.sessionId) ||
        (row.status === 200 ? !row.text : row.text !== '')) return fail('invalid_request');
    return Object.freeze({ ...base, status: row.status as 200 | 202 | 204 | 405, text: row.text, sessionId: row.sessionId });
  } catch { return fail('invalid_request'); }
}

/**
 * 既有 SDK 的手机 send 端口。宿主须传入认证身份及最新任务／设备／服务授权检查，不能接模型参数。
 * 内存通道不恢复执行；take 消费交付权。发现路由已接入，工具执行路由仍待接线。
 */
export class NativeMcpChannel {
  #binding: Binding;
  #authorize: (signal: AbortSignal) => Promise<void>;
  #deadline: number;
  #closed = false;
  #busy = false;
  #count = 0;
  #totalBytes = 0;
  #receipts = new Map<string, string>();
  #active: AbortController | undefined;
  #pending: { packet: NativeMcpRequest; delivered: boolean; finish: (reply?: Reply) => void } | undefined;

  constructor(binding: Binding, authorize: (signal: AbortSignal) => Promise<void>, timeoutMs = 10000) {
    try {
      const { ownerId, deviceId, requestId, taskId, serviceId, revision, generation, endpoint } = binding;
      if (typeof ownerId !== 'string' || !ownerId || ownerId.length > 128 || typeof authorize !== 'function' ||
          !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 30000) fail('invalid_request');
      this.#binding = Object.freeze({ ownerId, deviceId: uuid(deviceId), requestId: uuid(requestId), taskId: uuid(taskId),
        serviceId: uuid(serviceId), revision: uuid(revision), generation: uuid(generation), endpoint: normalizeMcpEndpoint(endpoint) });
      this.#authorize = authorize;
      this.#deadline = performance.now() + timeoutMs;
    } catch { throw new NativeMcpError('invalid_request'); }
  }

  #check(signal?: AbortSignal) {
    if (this.#closed || performance.now() >= this.#deadline || signal?.aborted) return fail('inactive');
  }

  #actor(actor: Principal) {
    if (actor.ownerId !== this.#binding.ownerId || actor.deviceId !== this.#binding.deviceId) return fail('scope_denied');
    this.#check();
  }

  async #authorized(signal: AbortSignal) {
    this.#check(signal);
    await new Promise<void>((resolve, reject) => {
      const finish = (error?: NativeMcpError) => {
        clearTimeout(timer); signal.removeEventListener('abort', abort);
        if (error) reject(error); else resolve();
      };
      const abort = () => finish(new NativeMcpError('inactive'));
      const timer = setTimeout(abort, Math.max(0, this.#deadline - performance.now()));
      signal.addEventListener('abort', abort, { once: true });
      // 消费迟到拒绝；外部异常及秘密正文不穿过此边界。
      Promise.resolve().then(() => this.#authorize(signal)).then(value =>
        finish(value === undefined ? undefined : new NativeMcpError('inactive')), () => finish(new NativeMcpError('inactive')));
      if (signal.aborted) abort();
    });
    this.#check(signal);
  }

  async send(request: Request): Promise<Response> {
    this.#check(request.signal);
    const headers: string[] = [];
    request.headers.forEach((_value, key) => { headers.push(key); });
    if (request.url !== this.#binding.endpoint || !['POST', 'GET', 'DELETE'].includes(request.method) ||
        headers.some(key => !['accept', 'content-type', 'cache-control', 'mcp-session-id', 'mcp-protocol-version'].includes(key)))
      return fail('invalid_request');
    // 首版不监听独立通知流；由宿主直接返回不支持，不占用顺序 POST／DELETE 的交付槽，也不出网。
    if (request.method === 'GET') return new Response(null, { status: 405 });
    if (this.#busy || ++this.#count > 13) return fail('limit_exceeded');
    this.#busy = true;
    const controller = new AbortController();
    this.#active = controller;
    const abort = () => controller.abort();
    const timer = setTimeout(abort, Math.max(0, this.#deadline - performance.now()));
    request.signal.addEventListener('abort', abort, { once: true });
    if (request.signal.aborted) abort();
    const signal = controller.signal;
    try {
      const sessionId = request.headers.get('mcp-session-id');
      const protocolVersion = request.headers.get('mcp-protocol-version');
      if (!session(sessionId) || (protocolVersion !== null && !['2025-11-25', '2025-06-18', '2025-03-26'].includes(protocolVersion)))
        return fail('invalid_request');
      // 总时限也覆盖 SDK 正文读取，不依赖外部调用者主动取消悬挂流。
      const raw = await readBody(new Request(request, { signal }), request.method === 'POST' ? 16384 : 0);
      const body = request.method === 'POST' ? new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(raw) : null;
      if (body === '') return fail('invalid_request');
      await this.#authorized(signal);
      const { ownerId: _owner, deviceId: _device, ...scope } = this.#binding;
      const packet: NativeMcpRequest = Object.freeze({ ...scope, version: 'native_mcp_v1', exchangeId: crypto.randomUUID(),
        method: request.method as 'POST' | 'DELETE', body, sessionId, protocolVersion });
      return await new Promise<Response>((resolve, reject) => {
        const finish = (reply?: Reply) => {
          signal.removeEventListener('abort', abortPending);
          this.#pending = undefined;
          if (!reply || 'error' in reply) { this.#closed = true; reject(new NativeMcpError(reply ? 'transport_failed' : 'inactive')); return; }
          resolve(new Response(reply.status === 200 ? reply.text : null, { status: reply.status, headers: {
            ...(reply.status === 200 ? { 'Content-Type': 'application/json' } : {}),
            ...(reply.sessionId === null ? {} : { 'Mcp-Session-Id': reply.sessionId }),
          } }));
        };
        const abortPending = () => finish();
        this.#pending = { packet, delivered: false, finish };
        signal.addEventListener('abort', abortPending, { once: true });
        if (signal.aborted) abortPending();
      });
    } catch (error) {
      const failure = error instanceof NativeMcpError ? error : new NativeMcpError(signal.aborted ? 'inactive' : 'invalid_request');
      this.close();
      throw failure;
    } finally {
      clearTimeout(timer); request.signal.removeEventListener('abort', abort);
      this.#active = undefined; this.#busy = false;
    }
  }

  /** 非幂等交付：失联后宁可明确未知，也不能重新派发写操作。HTTP 宿主不得自动重试此动作。 */
  async take(actor: Principal): Promise<NativeMcpRequest | null> {
    this.#actor(actor);
    const pending = this.#pending;
    if (!pending || pending.delivered) return null;
    await this.#authorized(new AbortController().signal);
    if (this.#pending !== pending || pending.delivered) return null;
    pending.delivered = true;
    return pending.packet;
  }

  async reply(actor: Principal, input: unknown): Promise<void> {
    this.#actor(actor);
    const reply = parseNativeMcpReply(input);
    if (reply.generation !== this.#binding.generation) return fail('conflict');
    const fingerprint = await digest(JSON.stringify(reply));
    await this.#authorized(new AbortController().signal);
    const prior = this.#receipts.get(reply.exchangeId);
    if (prior !== undefined) { if (prior === fingerprint) return; return fail('conflict'); }
    const pending = this.#pending;
    if (!pending || !pending.delivered || pending.packet.exchangeId !== reply.exchangeId) return fail('inactive');
    if ('text' in reply && (this.#totalBytes += bytes(reply.text)) > 262144) { this.close(); return fail('limit_exceeded'); }
    this.#receipts.set(reply.exchangeId, fingerprint);
    if ('error' in reply) console.warn(JSON.stringify({ event: 'native_mcp_exchange_failed',
      taskId: this.#binding.taskId, exchangeId: reply.exchangeId, code: reply.error }));
    pending.finish(reply);
  }

  close(): void {
    this.#closed = true; this.#active?.abort(); this.#pending?.finish();
    this.#pending = undefined; this.#receipts.clear();
  }
}
