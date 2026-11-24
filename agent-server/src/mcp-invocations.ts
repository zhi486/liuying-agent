import type { BudgetDatabase } from './d1.ts';
import { digest, type Principal } from './device-auth.ts';
import { DATABASE_TIME_SQL as time } from './cloud-task-contracts.ts';
import { authorizeMcpTool, encodeMcpJson, normalizeMcpEndpoint } from './mcp-registry.ts';
import { decodeMcpService } from './mcp-store.ts';
import { mcpSchemaValidator } from './mcp-schema.ts';
import type { McpDispatch, McpToolObservation } from './mcp-http.ts';

type Code = 'invalid_request' | 'unavailable' | 'conflict' | 'confirmation_required' |
  'inactive' | 'already_dispatched' | 'storage_failed' | 'invalid_state';
export class McpInvocationError extends Error {
  readonly code: Code;
  constructor(code: Code) { super('MCP 调用许可操作未完成'); this.code = code; }
}
const fail = (code: Code): never => { throw new McpInvocationError(code); };
const uuid = (s: unknown): s is string => typeof s === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(s);
const hash = (s: unknown): s is string => typeof s === 'string' && /^[0-9a-f]{64}$/.test(s);
const identifier = (s: unknown): s is string => typeof s === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(s);
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const bytes = (s: string) => new TextEncoder().encode(s).byteLength;
const check = (signal: AbortSignal) => { if (signal.aborted) fail('inactive'); };
const guard = async <T>(run: () => Promise<T>): Promise<T> => {
  try { return await run(); } catch (error) {
    throw error instanceof McpInvocationError ? error : new McpInvocationError('storage_failed');
  }
};
type Row = Record<string, unknown>;
type Options = { db: BudgetDatabase; principal: Principal };
type Execution = { taskId: string; token: string };
type Status = 'pending' | 'approved' | 'denied' | 'may_have_dispatched' | 'completed';
export type McpInvocationView = Readonly<{
  id: string; taskId: string; callId: string; serviceId: string; modelName: string; name: string;
  mode: 'read' | 'confirm'; status: Status; intentDigest: string; argumentsDigest: string;
  expiresAtMillis: number; expired: boolean; resultIsError: boolean | null;
}>;

function view(row: Row): McpInvocationView {
  if (!uuid(row.id) || !hash(row.intent_digest) || !hash(row.arguments_digest) ||
      !['pending', 'approved', 'denied', 'may_have_dispatched', 'completed'].includes(String(row.status)) ||
      (row.mode !== 'read' && row.mode !== 'confirm')) return fail('invalid_state');
  return Object.freeze({ id: row.id, taskId: String(row.task_id), callId: String(row.call_id),
    serviceId: String(row.service_id), modelName: String(row.model_name), name: String(row.tool_name),
    mode: row.mode, status: row.status as Status, intentDigest: row.intent_digest, argumentsDigest: row.arguments_digest,
    expiresAtMillis: Number(row.expires_at), expired: Number(row.observed_at) >= Number(row.expires_at),
    resultIsError: row.result_is_error === null ? null : row.result_is_error === 1 });
}

/** 在首次 await 前复制原参数；模型不能改变身份、授权模式或批准后的参数。 */
function snapshot(input: McpDispatch, ownerId: string): McpDispatch {
  try {
    const copy: unknown = JSON.parse(encodeMcpJson(input));
    if (!object(copy) || Object.keys(copy).length !== 9 || copy.ownerId !== ownerId ||
        !uuid(copy.serviceId) || !uuid(copy.revision) || !hash(copy.fingerprint) ||
        typeof copy.endpoint !== 'string' || normalizeMcpEndpoint(copy.endpoint) !== copy.endpoint ||
        typeof copy.modelName !== 'string' || !/^mcp_[0-9a-f]{32}_[0-9a-z]{1,2}$/.test(copy.modelName) ||
        typeof copy.name !== 'string' || !/^[A-Za-z0-9_.-]{1,128}$/.test(copy.name) ||
        (copy.mode !== 'read' && copy.mode !== 'confirm') || typeof copy.argumentsJson !== 'string' || bytes(copy.argumentsJson) > 8192)
      return fail('invalid_request');
    const args: unknown = JSON.parse(copy.argumentsJson);
    if (!object(args) || encodeMcpJson(args) !== copy.argumentsJson) return fail('invalid_request');
    return Object.freeze(copy) as McpDispatch;
  } catch { return fail('invalid_request'); }
}

function context({ db, principal }: Options, execution?: { taskId: string; hash: string }) {
  const { ownerId, deviceId } = principal;
  if (typeof ownerId !== 'string' || !ownerId || ownerId.length > 128 || !uuid(deviceId)) fail('invalid_request');
  const device = `EXISTS (SELECT 1 FROM agent_devices d WHERE d.id = ? AND d.owner_id = ?
    AND d.revoked_at IS NULL AND d.created_at <= ${time} AND d.expires_at > ${time})`;
  const read = async (key: string): Promise<Row> => {
    if (execution ? !identifier(key) : !uuid(key)) return fail('invalid_request');
    const row = (await db.prepare(`SELECT i.*, ${time} AS observed_at FROM agent_mcp_invocations i
      WHERE i.owner_id = ? AND i.device_id = ? AND ${execution ? 'i.task_id = ? AND i.call_id = ? AND i.execution_hash = ?' : 'i.id = ?'}
      AND ${device}`)
      .bind(ownerId, deviceId, ...(execution ? [execution.taskId, key, execution.hash] : [key]), deviceId, ownerId).all()).results[0];
    if (!row) return fail('unavailable');
    return row;
  };
  const service = async (dispatch: Pick<McpDispatch, 'serviceId' | 'revision' | 'modelName' | 'name' | 'mode' | 'endpoint' | 'fingerprint'>,
    argumentsJson?: string) => {
    const row = (await db.prepare(`SELECT * FROM agent_mcp_services WHERE id = ? AND owner_id = ? AND ${device}`)
      .bind(dispatch.serviceId, ownerId, deviceId, ownerId).all()).results[0];
    if (!row) return fail('inactive');
    let decoded;
    try { decoded = await decodeMcpService(row); } catch { return fail('invalid_state'); }
    const allowed = authorizeMcpTool(decoded, ownerId, dispatch.revision, dispatch.modelName);
    if (allowed.status === 'rejected' || allowed.name !== dispatch.name || decoded.endpoint !== dispatch.endpoint ||
        decoded.catalog?.fingerprint !== dispatch.fingerprint ||
        (allowed.status === 'allowed_read' ? 'read' : 'confirm') !== dispatch.mode) return fail('inactive');
    if (argumentsJson !== undefined) {
      try {
        const definition = decoded.catalog.tools.find(tool => tool.name === dispatch.name)!.definition;
        if (!mcpSchemaValidator(definition.inputSchema)(JSON.parse(argumentsJson))) return fail('invalid_request');
      } catch { return fail('invalid_request'); }
    }
    return row;
  };
  // 最终写入再次比较完整原始快照，避免异步解析期间被换目录或撤销授权。
  const authority = (taskId: string, executionHash: string, raw: Row) => ({
    sql: `EXISTS (SELECT 1 FROM agent_cloud_tasks t JOIN agent_devices d ON d.id = t.device_id AND d.owner_id = t.owner_id
      WHERE t.id = ? AND t.owner_id = ? AND t.device_id = ? AND t.execution_hash = ? AND t.status = 'running'
      AND t.accepted_at <= ${time} AND t.deadline_at > ${time}
      AND d.revoked_at IS NULL AND d.created_at <= ${time} AND d.expires_at > ${time})
      AND EXISTS (SELECT 1 FROM agent_mcp_services s WHERE s.id = ? AND s.owner_id = ? AND s.enabled = 1
      AND s.endpoint = ? AND s.revision = ? AND s.catalog_fingerprint = ? AND s.catalog_json = ? AND s.grants_json = ?)`,
    values: [taskId, ownerId, deviceId, executionHash, raw.id, ownerId, raw.endpoint, raw.revision,
      raw.catalog_fingerprint, raw.catalog_json, raw.grants_json] as (string | number | null)[],
  });
  return { db, ownerId, deviceId, device, read, service, authority };
}

const fromRow = (row: Row) => ({ serviceId: String(row.service_id), revision: String(row.service_revision),
  modelName: String(row.model_name), name: String(row.tool_name), mode: row.mode as 'read' | 'confirm',
  endpoint: String(row.endpoint), fingerprint: String(row.catalog_fingerprint) });

/** 只能从已认证的原设备确认通路构造；不提供给模型，也不接受 confirmed 布尔值。 */
export function createMcpDecisions(options: Options) {
  const c = context(options);
  return {
    get: (id: string) => guard(async () => view(await c.read(id))),
    decide: (id: string, intentDigest: string, decision: 'approve' | 'deny') => guard(async () => {
      if (!hash(intentDigest) || !['approve', 'deny'].includes(decision)) return fail('invalid_request');
      const row = await c.read(id);
      if (row.intent_digest !== intentDigest) return fail('conflict');
      if (decision === 'deny' && row.status === 'denied') return view(row);
      if (decision === 'approve' && row.confirmed_at !== null && ['approved', 'may_have_dispatched', 'completed'].includes(String(row.status)))
        return view(row);
      if ((decision === 'approve' && (row.mode !== 'confirm' || row.status !== 'pending')) ||
          (decision === 'deny' && !['pending', 'approved'].includes(String(row.status)))) return fail('inactive');
      const raw = await c.service(fromRow(row));
      const authority = c.authority(String(row.task_id), String(row.execution_hash), raw);
      const result = await c.db.prepare(`UPDATE agent_mcp_invocations SET status = ?,
        confirmed_at = CASE WHEN ? = 'approve' THEN ${time} ELSE confirmed_at END
        WHERE id = ? AND owner_id = ? AND device_id = ? AND intent_digest = ? AND status = ?
        AND expires_at > ${time} AND ${authority.sql}`)
        .bind(decision === 'approve' ? 'approved' : 'denied', decision, id, c.ownerId, c.deviceId, intentDigest,
          String(row.status), ...authority.values).run();
      if (result.meta.changes !== 1) return fail('inactive');
      return view(await c.read(id));
    }),
  };
}

/** 私有执行凭证只属于服务端执行器；领取成功后保守地视为可能已发送，不自动重放。 */
export async function createMcpInvocations(options: Options & { execution: Execution }) {
  const { taskId, token } = options.execution;
  const principal = { ...options.principal };
  const db = options.db;
  if (!uuid(taskId) || !hash(token)) return fail('invalid_request');
  const executionHash = await digest(token);
  const c = context({ db, principal }, { taskId, hash: executionHash });
  return guard(async () => {
    const bound = (await c.db.prepare(`SELECT id FROM agent_cloud_tasks WHERE id = ? AND owner_id = ?
      AND device_id = ? AND execution_hash = ? AND ${c.device}`)
      .bind(taskId, c.ownerId, c.deviceId, executionHash, c.deviceId, c.ownerId).all()).results[0];
    if (!bound) return fail('unavailable');
    const intent = async (callId: string, input: McpDispatch) => {
      if (!identifier(callId)) return fail('invalid_request');
      const dispatch = snapshot(input, c.ownerId);
      const argumentsDigest = await digest(dispatch.argumentsJson);
      const { argumentsJson: _argumentsJson, ...metadata } = dispatch;
      const intentDigest = await digest(encodeMcpJson({ version: 1, deviceId: c.deviceId,
        taskId, executionHash, callId, ...metadata, argumentsDigest }));
      return { dispatch, argumentsDigest, intentDigest };
    };
    return {
      get: (callId: string) => guard(async () => view(await c.read(callId))),
      prepare: (callId: string, input: McpDispatch, signal: AbortSignal) => guard(async () => {
        check(signal);
        const { dispatch: d, argumentsDigest, intentDigest } = await intent(callId, input);
        const raw = await c.service(d, d.argumentsJson);
        const authority = c.authority(taskId, executionHash, raw);
        check(signal);
        const results = await c.db.batch([
          c.db.prepare(`INSERT OR IGNORE INTO agent_mcp_invocations
            (id,owner_id,device_id,task_id,execution_hash,call_id,service_id,endpoint,service_revision,catalog_fingerprint,
             model_name,tool_name,mode,arguments_digest,intent_digest,status,created_at,expires_at)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,${time},MIN(t.deadline_at,d.expires_at)
            FROM agent_cloud_tasks t JOIN agent_devices d ON d.id = t.device_id AND d.owner_id = t.owner_id
            WHERE t.id = ? AND ${authority.sql}
            AND (SELECT COUNT(*) FROM agent_mcp_invocations WHERE task_id = ?) < 6`)
            .bind(crypto.randomUUID(), c.ownerId, c.deviceId, taskId, executionHash, callId, d.serviceId, d.endpoint,
              d.revision, d.fingerprint, d.modelName, d.name, d.mode, argumentsDigest, intentDigest,
              d.mode === 'read' ? 'approved' : 'pending', taskId, ...authority.values, taskId),
          c.db.prepare(`SELECT *, ${time} AS observed_at FROM agent_mcp_invocations WHERE task_id = ? AND call_id = ?
            AND owner_id = ? AND device_id = ? AND execution_hash = ? AND ${c.device}`)
            .bind(taskId, callId, c.ownerId, c.deviceId, executionHash, c.deviceId, c.ownerId),
        ]);
        check(signal);
        const row = results[1]?.results[0];
        if (!row || row.intent_digest !== intentDigest) return fail('conflict');
        return view(row);
      }),
      claim: (callId: string, input: McpDispatch, signal: AbortSignal) => guard(async () => {
        check(signal);
        const { dispatch: d, intentDigest } = await intent(callId, input);
        const row = await c.read(callId);
        if (row.intent_digest !== intentDigest) return fail('conflict');
        const unavailable = (current: Row): never => {
          if (['may_have_dispatched', 'completed'].includes(String(current.status))) return fail('already_dispatched');
          if (current.status === 'pending' && Number(current.observed_at) < Number(current.expires_at)) return fail('confirmation_required');
          return fail('inactive');
        };
        if (row.status !== 'approved') return unavailable(row);
        const raw = await c.service(d, d.argumentsJson);
        const authority = c.authority(taskId, executionHash, raw);
        check(signal);
        const result = await c.db.prepare(`UPDATE agent_mcp_invocations SET status = 'may_have_dispatched', dispatched_at = ${time}
          WHERE id = ? AND owner_id = ? AND device_id = ? AND execution_hash = ? AND intent_digest = ?
          AND status = 'approved' AND expires_at > ${time} AND ${authority.sql}`)
          .bind(String(row.id), c.ownerId, c.deviceId, executionHash, intentDigest, ...authority.values).run();
        check(signal);
        if (result.meta.changes !== 1) return unavailable(await c.read(callId));
      }),
      complete: (callId: string, input: McpDispatch, observation: McpToolObservation) => guard(async () => {
        const { dispatch: d, intentDigest } = await intent(callId, input);
        // 只接收可信执行器的有界回执；不因撤权丢掉已发生的结果摘要，也不恢复任何权限。
        let receipt: string;
        try {
          receipt = encodeMcpJson(observation);
          const value: unknown = JSON.parse(receipt);
          if (bytes(receipt) > 16384 || !object(value) || Object.keys(value).some(key =>
            !['source', 'serviceId', 'modelName', 'isError', 'content', 'structuredContent', 'truncated'].includes(key)) ||
            ('truncated' in value && value.truncated !== true) ||
            value.source !== 'mcp' || value.serviceId !== d.serviceId || value.modelName !== d.modelName ||
            typeof value.isError !== 'boolean' || !Array.isArray(value.content) || value.content.some(part =>
              !object(part) || Object.keys(part).length !== 2 || part.type !== 'text' || typeof part.text !== 'string')) return fail('invalid_request');
        } catch { return fail('invalid_request'); }
        const resultIsError = (JSON.parse(receipt) as McpToolObservation).isError ? 1 : 0;
        const resultDigest = await digest(receipt);
        const results = await c.db.batch([
          c.db.prepare(`UPDATE agent_mcp_invocations SET status = 'completed', result_digest = ?, result_is_error = ?
            WHERE task_id = ? AND call_id = ? AND owner_id = ? AND device_id = ? AND execution_hash = ?
            AND intent_digest = ? AND status = 'may_have_dispatched'`)
            .bind(resultDigest, resultIsError, taskId, callId, c.ownerId, c.deviceId, executionHash, intentDigest),
          c.db.prepare(`SELECT status,result_digest,intent_digest FROM agent_mcp_invocations
            WHERE task_id = ? AND call_id = ? AND owner_id = ? AND device_id = ? AND execution_hash = ?`)
            .bind(taskId, callId, c.ownerId, c.deviceId, executionHash),
        ]);
        const row = results[1]?.results[0];
        if (!row) return fail('unavailable');
        if (row.intent_digest !== intentDigest || (row.status === 'completed' && row.result_digest !== resultDigest)) return fail('conflict');
        if (row.status !== 'completed') return fail('invalid_state');
      }),
    };
  });
}
