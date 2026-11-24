import { CloudError, uuid } from './cloud-task-contracts.ts';
import { digest, type Principal } from './device-auth.ts';
import { exactObject } from './tools.ts';
import { readBody } from './agent-http.ts';

type Source = Readonly<{ requestId: string; prompt: string; answer: string }>;
export type SummaryInput = Readonly<{
  version: 'summary_v1'; requestId: string; fingerprint: string; approved: true; sources: readonly Source[];
}>;
export type SummaryRow = Readonly<{
  requestId: string; fingerprint: string; inputHash: string;
  state: 'attempted' | 'completed' | 'failed' | 'withdrawn';
  remoteFinished: boolean; summary: string | null;
  usage: Readonly<{ inputTokens: number; outputTokens: number }> | null;
}>;
/** 适配器须原子读改写、提交成功才返回；不能把此契约当作持久实现。 */
export interface SummaryStore {
  /** 原子插入，不覆盖已有行；created 仅表示本次已提交的新建。 */
  create(principal: Principal, row: SummaryRow): Promise<{ row: SummaryRow; created: boolean }>;
  change(principal: Principal, id: string, change: (row: SummaryRow | null) => SummaryRow): Promise<SummaryRow>;
  read(principal: Principal, id: string): Promise<SummaryRow | null>;
}
export interface SummaryModel {
  generate(input: SummaryInput): Promise<{ text: string; inputTokens: number; outputTokens: number }>;
}
export interface SummaryAccounting {
  begin(principal: Principal, id: string, hash: string): Promise<boolean>;
  record(principal: Principal, id: string, hash: string, usage: NonNullable<SummaryRow['usage']>): Promise<void>;
}

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value))
    throw new CloudError('invalid_request');
  return value;
}
function tokens(value: number) { return Number.isSafeInteger(value) && value >= 0; }

export function parseSummary(value: unknown): SummaryInput {
  try {
    const row = exactObject(value, ['version', 'requestId', 'fingerprint', 'approved', 'sources'], 'invalid_task');
    if (row.version !== 'summary_v1' || row.approved !== true || typeof row.fingerprint !== 'string' ||
        !/^[0-9a-f]{64}$/.test(row.fingerprint) || !Array.isArray(row.sources) ||
        row.sources.length < 1 || row.sources.length > 8) throw new CloudError('invalid_request');
    const sources = row.sources.map((value) => {
      const source = exactObject(value, ['requestId', 'prompt', 'answer'], 'invalid_task');
      return Object.freeze({ requestId: uuid(source.requestId), prompt: text(source.prompt, 12000), answer: text(source.answer, 4000) });
    });
    if (new Set(sources.map((source) => source.requestId)).size !== sources.length ||
        sources.reduce((size, source) => size + source.prompt.length + source.answer.length, 0) > 12000)
      throw new CloudError('invalid_request');
    return Object.freeze({ version: 'summary_v1', requestId: uuid(row.requestId), fingerprint: row.fingerprint,
      approved: true, sources: Object.freeze(sources) });
  } catch { throw new CloudError('invalid_request'); }
}

/** 尚未挂入Worker；正式启用前仍须完成资源上限、授权及记账接线验收。 */
export function createSummaryService(options: {
  authenticate: (authorization: string | null) => Promise<Principal>; store: SummaryStore; model: SummaryModel;
  accounting?: SummaryAccounting;
}) {
  const { store, model } = options;
  return async function handle(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.protocol !== 'https:' || url.search || url.hash || url.username || url.password || request.headers.has('content-encoding'))
        throw new CloudError('invalid_request');
      const match = /^\/v1\/agent\/summaries\/([0-9a-f-]+)(\/withdraw)?$/.exec(url.pathname);
      const create = url.pathname === '/v1/agent/summaries' && request.method === 'POST';
      if (!create && (!match || request.method !== (match[2] ? 'POST' : 'GET'))) throw new CloudError('invalid_request');
      const principal = await options.authenticate(request.headers.get('Authorization'));
      const deliver = async (current: SummaryRow): Promise<Response> => {
        let authorized = false;
        try {
          const active = await options.authenticate(request.headers.get('Authorization'));
          authorized = active.ownerId === principal.ownerId && active.deviceId === principal.deviceId;
        } catch { /* 交付前无法复核身份时拒绝正文。 */ }
        if (!authorized) {
          await store.change(principal, current.requestId, (saved) => {
            if (!saved || saved.inputHash !== current.inputHash) throw new CloudError('conflict');
            return { ...saved, state: 'withdrawn', summary: null };
          });
          throw new CloudError('unauthenticated');
        }
        // 补记及身份复核可能等待，不能交付等待前的旧正文。
        const latest = await store.read(principal, current.requestId);
        if (!latest || latest.inputHash !== current.inputHash) throw new CloudError('unavailable');
        return response(latest);
      };
      let row: SummaryRow;
      if (create) {
        if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('content-type') ?? ''))
          throw new CloudError('invalid_request');
        const bytes = await readBody(request, 100000);
        let value: unknown;
        try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes)); }
        catch { throw new CloudError('invalid_request'); }
        const input = parseSummary(value);
        const inputHash = await digest(JSON.stringify(input));
        const accepted = await store.create(principal, { requestId: input.requestId, fingerprint: input.fingerprint,
          inputHash, state: 'attempted', remoteFinished: false, summary: null, usage: null });
        row = accepted.row;
        if (row.inputHash !== inputHash) throw new CloudError('conflict');
        if (accepted.created) {
          if (options.accounting && !await options.accounting.begin(principal, input.requestId, inputHash))
            throw new CloudError('unavailable');
          const active = await options.authenticate(request.headers.get('Authorization'));
          if (active.ownerId !== principal.ownerId || active.deviceId !== principal.deviceId)
            throw new CloudError('unauthenticated');
          // 无生成器重试；异常可能发生在派发之后，保留未知状态供查询。
          let result: Awaited<ReturnType<SummaryModel['generate']>> | undefined;
          try { result = await model.generate(input); } catch { /* 失联后只查询账本，不重发。 */ }
          if (!result || !tokens(result.inputTokens) || !tokens(result.outputTokens)) {
            const current = await store.read(principal, input.requestId);
            if (!current || current.inputHash !== inputHash) throw new CloudError('unavailable');
            return await deliver(current);
          }
          const usage = { inputTokens: result.inputTokens, outputTokens: result.outputTokens };
          let receiptFailed = false;
          try { await options.accounting?.record(principal, input.requestId, inputHash, usage); }
          catch { receiptFailed = true; }
          let authorized = false;
          try {
            const active = await options.authenticate(request.headers.get('Authorization'));
            authorized = active.ownerId === principal.ownerId && active.deviceId === principal.deviceId;
          } catch { /* 无法复核授权时不保留可交付正文。 */ }
          row = await store.change(principal, input.requestId, (current) => {
            if (!current || current.inputHash !== inputHash) throw new CloudError('conflict');
            let summary: string | null = null;
            try { summary = text(result.text, 2000); } catch { /* 非法正文仍保留实际用量。 */ }
            const withdrawn = current.state === 'withdrawn' || !authorized;
            return { ...current, remoteFinished: true, usage,
              state: withdrawn ? 'withdrawn' : summary === null ? 'failed' : 'completed',
              summary: withdrawn ? null : summary };
          });
          if (!authorized) throw new CloudError('unauthenticated');
          if (receiptFailed) throw new CloudError('unavailable');
        }
      } else {
        const id = uuid(match![1]);
        await readBody(request, 0);
        if (match![2]) row = await store.change(principal, id, (current) => {
          if (!current) throw new CloudError('not_found');
          return { ...current, state: 'withdrawn', summary: null };
        });
        else {
          const current = await store.read(principal, id);
          if (!current) throw new CloudError('not_found');
          row = current;
        }
      }
      // 查询或重复提交只同步已保存的可信用量，不重新生成。
      if (row.usage && options.accounting)
        await options.accounting.record(principal, row.requestId, row.inputHash, row.usage);
      return await deliver(row);
    } catch (error) {
      const code = error instanceof CloudError ? error.code : 'unavailable';
      return Response.json({ error: code }, { status: code === 'unauthenticated' ? 401 : code === 'not_found' ? 404 :
        code === 'conflict' ? 409 : code === 'invalid_request' ? 400 : 503 });
    }
  };
}
function response(row: SummaryRow) {
  return Response.json({ version: 'summary_v1', purpose: 'conversation_summary', requestId: row.requestId,
    fingerprint: row.fingerprint, state: row.state, remoteFinished: row.remoteFinished, summary: row.summary,
    cost: row.usage === null ? 'uncertain' : 'usage', usage: row.usage }, { headers: { 'Cache-Control': 'no-store' } });
}
