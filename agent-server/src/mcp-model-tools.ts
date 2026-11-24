import { AgentError, type McpModelCall, type McpModelPort } from './contracts.ts';
import type { BudgetDatabase } from './d1.ts';
import type { Principal } from './device-auth.ts';
import { createMcpStore } from './mcp-store.ts';
import { createMcpInvocations, type McpInvocationView } from './mcp-invocations.ts';
import { callMcpHttp, McpCallError, type McpDispatch } from './mcp-http.ts';
import { encodeMcpJson, type McpService } from './mcp-registry.ts';
import { mcpSchemaValidator } from './mcp-schema.ts';
import { validateToolCall } from './tools.ts';
import type { McpModelObservation } from './mcp-model-observation.ts';

/** 手机通道及单次确认由可信宿主提供；本模块不持有密钥，也没有全局 fetch 后备。 */
export async function createMcpModelTools(options: {
  db: BudgetDatabase; principal: Principal; execution: { taskId: string; token: string };
  services: readonly { serviceId: string; revision: string }[];
  send: (dispatch: McpDispatch, request: Request, invocation: McpInvocationView) => Promise<Response>;
  awaitConfirmation?: (invocation: McpInvocationView, dispatch: McpDispatch, signal: AbortSignal) => Promise<void>;
}): Promise<McpModelPort> {
  if (!options.services.length || options.services.length > 8 ||
      new Set(options.services.map(row => row.serviceId)).size !== options.services.length)
    throw new AgentError('invalid_tool');
  const principal = Object.freeze({ ...options.principal });
  const send = options.send;
  const awaitConfirmation = options.awaitConfirmation;
  if (typeof send !== 'function') throw new AgentError('invalid_tool');
  const store = createMcpStore({ db: options.db, principal, discover: async () => { throw new Error('调用入口不执行目录登记'); } });
  const entries = new Map<string, { service: McpService; dispatch: Omit<McpDispatch, 'argumentsJson'>; validate: (args: unknown) => boolean }>();
  const catalog: McpModelPort['catalog'][number][] = [];
  // 在任何 await 前拷贝绑定，避免宿主等待数据库时被外部对象改写。
  const bindings = options.services.map(row => ({ ...row }));
  const invocations = await createMcpInvocations({ db: options.db, principal, execution: { ...options.execution } });
  for (const binding of bindings) {
    const service = await store.get(binding.serviceId);
    if (service.revision !== binding.revision || !service.enabled || !service.catalog)
      throw new AgentError('scope_denied');
    for (const grant of service.grants) {
      const tool = service.catalog.tools.find(row => row.name === grant.name)!;
      const execution = tool.definition.execution;
      // 必须异步任务、未知 schema 等暂不支持的工具不向模型暴露。
      if (execution && typeof execution === 'object' && !Array.isArray(execution) &&
          'taskSupport' in execution && execution.taskSupport === 'required') continue;
      let validate: (args: unknown) => boolean;
      try {
        validate = mcpSchemaValidator(tool.definition.inputSchema);
        if ('outputSchema' in tool.definition) mcpSchemaValidator(tool.definition.outputSchema);
      } catch { continue; }
      if (entries.size >= 64) throw new AgentError('tool_limit');
      entries.set(grant.modelName, { service, validate, dispatch: Object.freeze({ serviceId: service.id, ownerId: principal.ownerId,
        endpoint: service.endpoint, revision: service.revision, fingerprint: service.catalog.fingerprint,
        modelName: grant.modelName, name: grant.name, mode: grant.mode }) });
      catalog.push(Object.freeze({ modelName: grant.modelName, description: typeof tool.definition.description === 'string'
        ? tool.definition.description : tool.name, parameters: tool.definition.inputSchema, mode: grant.mode }));
    }
  }
  if (new TextEncoder().encode(encodeMcpJson(catalog)).length > 24576) throw new AgentError('tool_limit');
  const validated = (input: McpModelCall) => {
    const call = validateToolCall(input);
    if (call.name !== 'mcp.call') throw new AgentError('invalid_tool');
    const entry = entries.get(call.args.modelName);
    if (!entry) throw new AgentError('scope_denied');
    if (!entry.validate(JSON.parse(call.args.argumentsJson))) throw new AgentError('invalid_tool');
    return { call, entry, dispatch: Object.freeze({ ...entry.dispatch, argumentsJson: call.args.argumentsJson }) };
  };
  let uncertain = false;
  return {
    catalog: Object.freeze(catalog),
    validate(input) { validated(input); },
    async execute(input, signal): Promise<McpModelObservation> {
      signal.throwIfAborted();
      const { call, entry, dispatch } = validated(input);
      if (uncertain) return { status: 'unconfirmed', reason: 'unavailable' };
      let claimed = false;
      let invocationId: string | undefined;
      let stage: 'prepare' | 'confirmation' | 'authority' | 'http' | 'receipt' = 'prepare';
      try {
        const prepared = await invocations.prepare(call.id, dispatch, signal);
        invocationId = prepared.id;
        if (prepared.status === 'pending') {
          if (!awaitConfirmation) return { status: 'rejected', reason: 'confirmation_required' };
          stage = 'confirmation';
          await awaitConfirmation(prepared, dispatch, signal);
        }
        stage = 'authority';
        signal.throwIfAborted();
        const current = await invocations.get(call.id);
        if (current.status === 'denied') return { status: 'rejected', reason: 'declined' };
        if (current.status === 'pending') return { status: 'rejected', reason: 'confirmation_required' };
        if (current.status !== 'approved' || current.expired) {
          uncertain = true;
          return { status: 'unconfirmed', reason: 'unavailable' };
        }
        // 握手前再读；SDK 也会重取远端目录，最终发 tools/call 前由账本原子领取。
        const latest = await store.get(dispatch.serviceId);
        if (latest.revision !== entry.service.revision || !latest.enabled)
          return { status: 'rejected', reason: 'not_authorized' };
        stage = 'http';
        const result = await callMcpHttp({ service: latest, actor: principal.ownerId, revision: dispatch.revision,
          modelName: dispatch.modelName, arguments: JSON.parse(dispatch.argumentsJson), signal,
          send: request => send(dispatch, request, current),
          async authorizeDispatch(actual, currentSignal) {
            if (encodeMcpJson(actual) !== encodeMcpJson(dispatch)) throw new Error('调用意图不匹配');
            await invocations.claim(call.id, dispatch, currentSignal);
            claimed = true;
          } });
        signal.throwIfAborted();
        stage = 'receipt';
        await invocations.complete(call.id, dispatch, result);
        return { status: 'completed', result };
      } catch (error) {
        if (signal.aborted) throw error;
        // 只记录固定阶段和错误码；不输出远端异常、参数、端点或结果正文。
        console.warn(JSON.stringify({ event: 'mcp_call_failed', invocationId, stage,
          phase: error instanceof McpCallError ? error.phase : 'unavailable',
          code: error instanceof McpCallError ? error.code : 'internal_failure',
          dispatchState: error instanceof McpCallError ? error.dispatchState : 'unknown', claimAcknowledged: claimed }));
        if (!claimed && error instanceof McpCallError && error.code === 'catalog_changed')
          return { status: 'rejected', reason: 'catalog_changed' };
        // 断连、存储失败和派发后异常不能判为没执行；本任务不再派发其他插件调用。
        uncertain = true;
        return { status: 'unconfirmed', reason: 'unavailable' };
      }
    },
  };
}

