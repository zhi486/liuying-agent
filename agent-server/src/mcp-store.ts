import type { BudgetDatabase } from './d1.ts';
import type { Principal } from './device-auth.ts';
import { DATABASE_TIME_SQL } from './cloud-task-contracts.ts';
import {
  approveMcpTools, collectMcpCatalog, createMcpService, disableMcpService,
  invalidateMcpCatalog, normalizeMcpEndpoint, replaceMcpEndpoint, setMcpCatalog,
  type McpCatalog, type McpService,
} from './mcp-registry.ts';

type Code = 'invalid_request' | 'invalid_state' | 'unavailable' | 'stale_review' | 'storage_failed' | 'discovery_failed';
export class McpStoreError extends Error {
  readonly code: Code;
  constructor(code: Code) { super('MCP 配置操作未完成'); this.code = code; }
}
const fail = (code: Code): never => { throw new McpStoreError(code); };
const uuid = (value: unknown): value is string => typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const size = (value: string) => new TextEncoder().encode(value).byteLength;
const policy = <T>(run: () => T): T => { try { return run(); } catch { return fail('invalid_request'); } };

/** 只从数据库重建；重新校验目录指纹及命名空间，不将损坏快照当作批准。 */
async function decode(row: Record<string, unknown>): Promise<McpService> {
  try {
    if (!uuid(row.id) || !uuid(row.revision) || typeof row.owner_id !== 'string' || !row.owner_id || row.owner_id.length > 128 ||
        typeof row.endpoint !== 'string' || normalizeMcpEndpoint(row.endpoint) !== row.endpoint ||
        (row.enabled !== 0 && row.enabled !== 1) || typeof row.grants_json !== 'string' || size(row.grants_json) > 16384)
      return fail('invalid_state');
    let catalog: McpCatalog | null = null;
    if (row.catalog_json !== null) {
      if (typeof row.catalog_json !== 'string' || size(row.catalog_json) > 131072) return fail('invalid_state');
      const definitions: unknown = JSON.parse(row.catalog_json);
      catalog = await collectMcpCatalog(async () => ({ tools: definitions }), new AbortController().signal);
      if (catalog.fingerprint !== row.catalog_fingerprint) return fail('invalid_state');
    } else if (row.catalog_fingerprint !== null) return fail('invalid_state');
    const grants: unknown = JSON.parse(row.grants_json);
    if (!Array.isArray(grants) || grants.length > 64) return fail('invalid_state');
    const base: McpService = Object.freeze({ id: row.id, ownerId: row.owner_id, endpoint: row.endpoint,
      revision: row.revision, enabled: false, catalog, grants: Object.freeze([]) });
    if (row.enabled === 0) {
      if (grants.length) return fail('invalid_state');
      return base;
    }
    if (grants.some(grant => !object(grant) || Object.keys(grant).length !== 3)) return fail('invalid_state');
    const approved = approveMcpTools(base, base.ownerId, base.revision,
      grants.map(grant => ({ name: grant.name, mode: grant.mode })));
    if (approved.grants.some((grant, index) => grant.modelName !== grants[index].modelName)) return fail('invalid_state');
    return Object.freeze({ ...approved, revision: base.revision });
  } catch { return fail('invalid_state'); }
}

// 调用许可也必须从同一权威快照重建，不能另用模型自报的批准状态。
export { decode as decodeMcpService };

function values(service: McpService) {
  return [service.endpoint, service.revision, service.enabled ? 1 : 0,
    service.catalog ? JSON.stringify(service.catalog.tools.map(tool => tool.definition)) : null,
    service.catalog?.fingerprint ?? null, JSON.stringify(service.grants)];
}

function validate(service: McpService) {
  const columns = values(service);
  return decode({ id: service.id, owner_id: service.ownerId, endpoint: columns[0], revision: columns[1], enabled: columns[2],
    catalog_json: columns[3], catalog_fingerprint: columns[4], grants_json: columns[5] });
}

/** 供认证后的可信管理通路调用；principal 不能来自请求正文，模型不能直接修改此状态。 */
export function createMcpStore(options: {
  db: BudgetDatabase; principal: Principal;
  discover: (endpoint: string, signal: AbortSignal) => Promise<McpCatalog>;
}) {
  const { db, discover } = options;
  const { ownerId, deviceId } = options.principal;
  if (!ownerId || ownerId.length > 128 || !uuid(deviceId)) fail('invalid_request');
  // 每条语句在数据库执行时重新核验设备，不能依赖页面打开时的有效身份。
  const active = `EXISTS (SELECT 1 FROM agent_devices WHERE id = ? AND owner_id = ?
    AND revoked_at IS NULL AND expires_at > ${DATABASE_TIME_SQL} AND created_at <= ${DATABASE_TIME_SQL})`;
  const guard = async <T>(run: () => Promise<T>): Promise<T> => {
    try { return await run(); } catch (error) {
      throw error instanceof McpStoreError ? error : new McpStoreError('storage_failed');
    }
  };
  const read = async (id: string): Promise<McpService> => {
    if (!uuid(id)) return fail('invalid_request');
    const row = (await db.prepare(`SELECT * FROM agent_mcp_services WHERE id = ? AND owner_id = ? AND ${active}`)
      .bind(id, ownerId, deviceId, ownerId).all()).results[0];
    if (!row) return fail('unavailable');
    return decode(row);
  };
  // 清理坏目录只读取可审核元数据，不把未能重建的目录当作工具授权。
  const summary = (row: Record<string, unknown>) => {
    if (!uuid(row.id) || !uuid(row.revision) || typeof row.endpoint !== 'string' || normalizeMcpEndpoint(row.endpoint) !== row.endpoint ||
        (row.enabled !== 0 && row.enabled !== 1)) return fail('invalid_state');
    return Object.freeze({ id: row.id, endpoint: row.endpoint, revision: row.revision, enabled: row.enabled === 1 });
  };
  const reviewed = async (id: string, revision: string) => {
    if (!uuid(revision)) return fail('invalid_request');
    const service = await read(id);
    if (service.revision !== revision) return fail('stale_review');
    return service;
  };
  const commit = async (before: McpService, after: McpService) => {
    const checked = await validate(after);
    const columns = values(checked);
    // 即使是相同目录也执行条件更新；异步读取后必须再次核验 revision 和当前身份。
    const result = await db.prepare(`UPDATE agent_mcp_services SET endpoint = ?, revision = ?, enabled = ?,
      catalog_json = ?, catalog_fingerprint = ?, grants_json = ? WHERE id = ? AND owner_id = ? AND revision = ? AND ${active}`)
      .bind(...columns, before.id, ownerId, before.revision, deviceId, ownerId).run();
    if (result.meta.changes !== 1) return fail('stale_review');
    return checked;
  };
  const change = (id: string, revision: string, update: (service: McpService) => McpService) => guard(async () => {
    const before = await reviewed(id, revision);
    return commit(before, policy(() => update(before)));
  });
  return {
    register: (endpoint: string) => guard(async () => {
      const service = policy(() => createMcpService(ownerId, endpoint));
      const result = await db.prepare(`INSERT INTO agent_mcp_services
        (endpoint, revision, enabled, catalog_json, catalog_fingerprint, grants_json, id, owner_id)
        SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE ${active}`)
        .bind(...values(service), service.id, ownerId, deviceId, ownerId).run();
      if (result.meta.changes !== 1) return fail('unavailable');
      return service;
    }),
    get: (id: string) => guard(() => read(id)),
    summary: (id: string) => guard(async () => {
      if (!uuid(id)) return fail('invalid_request');
      const row = (await db.prepare(`SELECT id, endpoint, revision, enabled FROM agent_mcp_services WHERE id = ? AND owner_id = ? AND ${active}`)
        .bind(id, ownerId, deviceId, ownerId).all()).results[0];
      if (!row) return fail('unavailable');
      return summary(row);
    }),
    list: (afterId: string | null = null) => guard(async () => {
      if (afterId !== null && !uuid(afterId)) return fail('invalid_request');
      const rows = (await db.prepare(`SELECT id, endpoint, revision, enabled FROM agent_mcp_services
        WHERE owner_id = ? AND (? IS NULL OR id > ?) AND ${active} ORDER BY id LIMIT 21`)
        .bind(ownerId, afterId, afterId, deviceId, ownerId).all()).results;
      // 列表不携带完整远端 schema，保持分页响应有界；详情读取时才重建批准快照。
      const items = rows.slice(0, 20).map(summary);
      return Object.freeze({ items: Object.freeze(items), nextCursor: rows.length > 20 ? items.at(-1)!.id : null });
    }),
    replaceEndpoint: (id: string, revision: string, endpoint: string) => change(id, revision, service => replaceMcpEndpoint(service, ownerId, endpoint)),
    approve: (id: string, revision: string, selection: unknown) => change(id, revision, service => approveMcpTools(service, ownerId, revision, selection)),
    disable: (id: string, revision: string) => change(id, revision, service => disableMcpService(service, ownerId)),
    invalidate: (id: string, revision: string) => change(id, revision, service => invalidateMcpCatalog(service, ownerId)),
    discover: (id: string, revision: string, signal: AbortSignal) => guard(async () => {
      const before = await reviewed(id, revision);
      let catalog: McpCatalog;
      try {
        if (signal.aborted) return fail('discovery_failed');
        // 目录只能来自构造工厂时注入的可信传输，不接收前端自报目录或摘要。
        const discovered = await discover(before.endpoint, signal);
        // 先重建目录再比较指纹，避免错误传输沿用旧指纹而绕过变更失效。
        const checked = await validate({ ...before, catalog: discovered, enabled: false, grants: [] });
        if (!checked.catalog) return fail('discovery_failed');
        catalog = checked.catalog;
        if (signal.aborted) return fail('discovery_failed');
      } catch {
        await commit(before, invalidateMcpCatalog(before, ownerId));
        return fail('discovery_failed');
      }
      return commit(before, setMcpCatalog(before, ownerId, catalog));
    }),
    remove: (id: string, revision: string) => guard(async () => {
      // 删除不反序列化远端目录：坏快照也必须能移除，但仍受身份和版本条件保护。
      if (!uuid(id) || !uuid(revision)) return fail('invalid_request');
      const result = await db.prepare(`DELETE FROM agent_mcp_services WHERE id = ? AND owner_id = ? AND revision = ? AND ${active}`)
        .bind(id, ownerId, revision, deviceId, ownerId).run();
      if (result.meta.changes !== 1) return fail('stale_review');
    }),
  };
}
