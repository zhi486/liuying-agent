import { CloudError, parseSubmission, uuid, type CloudErrorCode, type CloudTaskView } from './cloud-task-contracts.ts';
import { parseNativeMemoryReply, type NativeMemoryReply } from './native-memory-channel.ts';
import { parseMcpTaskPayload, type McpTaskAction } from './mcp-task-contracts.ts';
import { eventCursor, TASK_EVENTS_VERSION } from './task-events.ts';

export type AgentRoute =
  | { kind: 'submit'; input: unknown; streaming?: true }
  | { kind: 'events'; id: string; after: number }
  | { kind: 'memory'; id: string; reply?: NativeMemoryReply }
  | { kind: 'mcp'; id: string; action: McpTaskAction; input?: unknown }
  | { kind: 'get' | 'find' | 'cancel' | 'withdraw' | 'acknowledge' | 'purge'; id: string };

export class BodyError extends CloudError {
  readonly status: number;
  constructor(status: number) { super('invalid_request'); this.status = status; }
}

export async function readBody(request: Request, limit: number): Promise<Uint8Array> {
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const cancel = () => { void reader.cancel().catch(() => {}); };
  request.signal.addEventListener('abort', cancel, { once: true });
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      if (request.signal.aborted) throw new CloudError('invalid_request');
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) throw new BodyError(limit === 0 ? 400 : 413);
      chunks.push(value);
    }
    if (request.signal.aborted) throw new CloudError('invalid_request');
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
  } catch (error) {
    cancel();
    throw error instanceof CloudError ? error : new CloudError('invalid_request');
  } finally {
    request.signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}

export async function parseHttpRequest(request: Request): Promise<AgentRoute> {
  const url = new URL(request.url);
  if (url.protocol !== 'https:' || url.search || url.hash || url.username || url.password)
    throw new CloudError('invalid_request');
  if (request.headers.has('content-encoding')) throw new BodyError(415);
  const purge = /^\/v1\/agent\/requests\/([^/]+)\/purge$/.exec(url.pathname);
  if (purge) {
    if (request.method !== 'POST') throw new CloudError('invalid_request');
    await readBody(request, 0);
    return { kind: 'purge', id: uuid(purge[1]) };
  }
  const events = /^\/v1\/agent\/tasks\/([^/]+)\/events\/(.+)$/.exec(url.pathname);
  if (events) {
    if (request.method !== 'GET') throw new CloudError('invalid_request');
    await readBody(request, 0);
    return { kind: 'events', id: uuid(events[1]), after: eventCursor(events[2]) };
  }
  const mcp = /^\/v1\/agent\/tasks\/([^/]+)\/mcp(?:\/(take|reply|decision|close))?$/.exec(url.pathname);
  if (mcp) {
    const id = uuid(mcp[1]);
    const action = (mcp[2] ?? 'get') as McpTaskAction;
    if (request.method !== (action === 'get' ? 'GET' : 'POST')) throw new CloudError('invalid_request');
    if (action !== 'reply' && action !== 'decision') {
      await readBody(request, 0);
      return { kind: 'mcp', id, action };
    }
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? ''))
      throw new BodyError(415);
    const bytes = await readBody(request, action === 'reply' ? 800000 : 1024);
    try {
      const input = parseMcpTaskPayload(action, JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes)));
      return { kind: 'mcp', id, action, input };
    } catch { throw new CloudError('invalid_request'); }
  }
  const memory = /^\/v1\/agent\/tasks\/([^/]+)\/memory$/.exec(url.pathname);
  if (memory) {
    const id = uuid(memory[1]);
    if (request.method === 'GET') {
      await readBody(request, 0);
      return { kind: 'memory', id };
    }
    if (request.method !== 'POST') throw new CloudError('invalid_request');
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? ''))
      throw new BodyError(415);
    const bytes = await readBody(request, 16384);
    try {
      return { kind: 'memory', id, reply: parseNativeMemoryReply(JSON.parse(
        new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes))) };
    } catch { throw new CloudError('invalid_request'); }
  }
  if (url.pathname === '/v1/agent/tasks') {
    if (request.method !== 'POST') throw new CloudError('invalid_request');
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? ''))
      throw new BodyError(415);
    const bytes = await readBody(request, 65536);
    let value: unknown;
    try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes)); }
    catch { throw new CloudError('invalid_request'); }
    parseSubmission(value);
    const streaming = request.headers.get('X-Agent-Stream');
    if (streaming !== null && streaming !== TASK_EVENTS_VERSION) throw new CloudError('invalid_request');
    return { kind: 'submit', input: value, ...(streaming ? { streaming: true as const } : {}) };
  }
  const match = /^\/v1\/agent\/(tasks|requests)\/([^/]+)(?:\/(cancel|withdraw|acknowledge))?$/.exec(url.pathname);
  if (!match || (match[1] === 'requests' && match[3])) throw new CloudError('not_found');
  const id = uuid(match[2]);
  const action = match[3];
  if (request.method !== (action ? 'POST' : 'GET')) throw new CloudError('invalid_request');
  await readBody(request, 0);
  if (action === 'cancel' || action === 'withdraw' || action === 'acknowledge') return { kind: action, id };
  return { kind: match[1] === 'tasks' ? 'get' : 'find', id };
}

const statuses: Record<CloudErrorCode, number> = {
  invalid_request: 400, unauthenticated: 401, not_found: 404, conflict: 409,
  busy: 409, inactive: 409, request_expired: 410, unavailable: 503,
};
export function errorResponse(error: unknown): Response {
  const code = error instanceof CloudError ? error.code : 'unavailable';
  return Response.json({ error: code }, { status: error instanceof BodyError ? error.status : statuses[code],
    headers: { 'Cache-Control': 'no-store' } });
}

export function taskResponse(task: CloudTaskView, submission = false): Response {
  const status = submission && (task.status === 'queued' || task.status === 'running') ? 202 : 200;
  return Response.json({ task }, { status, headers: { 'Cache-Control': 'no-store' } });
}
