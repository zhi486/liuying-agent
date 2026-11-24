import type { AgentTask, McpModelPort } from './contracts.ts';
import type { BudgetDatabase } from './d1.ts';
import type { Principal } from './device-auth.ts';
import { CloudError } from './cloud-task-contracts.ts';
import { createExecutionLedger } from './execution-ledger.ts';
import { NativeMcpChannel } from './native-mcp-channel.ts';
import { createMcpStore } from './mcp-store.ts';
import { createMcpModelTools } from './mcp-model-tools.ts';
import { createMcpDecisions, type McpInvocationView } from './mcp-invocations.ts';
import { parseMcpTaskDecision } from './mcp-task-contracts.ts';
import type { McpDispatch } from './mcp-http.ts';

/** 本次任务的手机在线窗口。只存瞬时正文；进程恢复只查原账本，不恢复网络交付。 */
export async function createNativeMcpTask(options: {
  db: BudgetDatabase; principal: Principal; execution: { taskId: string; token: string }; requestId: string;
  configuration: NonNullable<AgentTask['mcpTools']>; signal: AbortSignal; active: () => boolean;
}) {
  const { db, signal, active } = options;
  const principal = Object.freeze({ ...options.principal });
  const execution = Object.freeze({ ...options.execution });
  const requestId = options.requestId;
  const services = options.configuration.services.map(row => Object.freeze({ ...row }));
  const controller = new AbortController();
  const channels = new Set<NativeMcpChannel>();
  const sessions = new WeakMap<McpDispatch, { channel: NativeMcpChannel; intent: ReturnType<typeof intent>; dispatch: McpDispatch }>();
  let current: { channel: NativeMcpChannel; intent: ReturnType<typeof intent>; dispatch: McpDispatch } | undefined;
  let pending: { intent: ReturnType<typeof intent>; dispatch: McpDispatch; finish: (error?: Error) => void } | undefined;
  const alive = () => {
    if (controller.signal.aborted || signal.aborted || !active()) throw new CloudError('inactive');
  };
  const actor = (value: Principal) => {
    if (value.ownerId !== principal.ownerId || value.deviceId !== principal.deviceId) throw new CloudError('unauthenticated');
    alive();
  };
  const close = () => {
    controller.abort();
    for (const channel of channels) channel.close();
    channels.clear(); current = undefined;
    pending?.finish(new CloudError('inactive')); pending = undefined;
    signal.removeEventListener('abort', close);
  };
  signal.addEventListener('abort', close, { once: true });
  const store = createMcpStore({ db, principal, discover: async () => { throw new CloudError('inactive'); } });
  const decisions = createMcpDecisions({ db, principal });
  let ledger: Awaited<ReturnType<typeof createExecutionLedger>>;
  const check = async () => { alive(); const ms = await ledger.check(controller.signal); alive(); return ms; };
  const serviceCheck = async (dispatch: McpDispatch) => {
    await check();
    const service = await store.get(dispatch.serviceId);
    alive();
    if (!service.enabled || service.revision !== dispatch.revision || service.endpoint !== dispatch.endpoint ||
        service.catalog?.fingerprint !== dispatch.fingerprint) throw new CloudError('inactive');
  };
  function intent(row: McpInvocationView, dispatch: McpDispatch) {
    const binding = services.find(value => value.serviceId === dispatch.serviceId && value.revision === dispatch.revision);
    if (!binding) throw new CloudError('inactive');
    return Object.freeze({ version: 'native_mcp_v1' as const, requestId, taskId: execution.taskId,
      serviceId: binding.serviceId, revision: binding.revision, generation: binding.generation,
      endpoint: dispatch.endpoint, invocationId: row.id, intentDigest: row.intentDigest, argumentsDigest: row.argumentsDigest,
      modelName: row.modelName, name: row.name, mode: row.mode, argumentsJson: dispatch.argumentsJson,
      expiresAtMillis: row.expiresAtMillis });
  }
  try {
    alive();
    ledger = await createExecutionLedger({ db, execution });
    if (ledger.ownerId !== principal.ownerId) throw new CloudError('unauthenticated');
    const port = await createMcpModelTools({ db, principal, execution, services,
      async awaitConfirmation(row, dispatch, callSignal) {
        await serviceCheck(dispatch);
        callSignal.throwIfAborted();
        if (pending) throw new CloudError('busy');
        const value = intent(row, dispatch);
        await new Promise<void>((resolve, reject) => {
          const abort = () => finish(new CloudError('inactive'));
          const finish = (error?: Error) => {
            callSignal.removeEventListener('abort', abort);
            if (pending?.intent === value) pending = undefined;
            if (error) reject(error); else resolve();
          };
          pending = { intent: value, dispatch, finish };
          callSignal.addEventListener('abort', abort, { once: true });
          if (callSignal.aborted || controller.signal.aborted) abort();
        });
        alive();
      },
      async send(dispatch, request, row) {
        await serviceCheck(dispatch);
        // 独立 GET 不交给手机，不占用有副作用的交付槽。
        if (request.method === 'GET') return new Response(null, { status: 405 });
        let session = sessions.get(dispatch);
        if (!session) {
          if (channels.size >= 6) throw new CloudError('inactive');
          const value = intent(row, dispatch);
          const remaining = Math.min(25000, await check());
          const channel = new NativeMcpChannel({ ...principal, ...value }, async () => serviceCheck(dispatch), remaining);
          session = { channel, intent: value, dispatch }; sessions.set(dispatch, session); channels.add(channel);
        }
        current = session;
        return session.channel.send(request);
      },
    });
    await check();
    const guardedPort: McpModelPort = { catalog: port.catalog, validate: call => port.validate(call),
      async execute(call, callSignal) {
        alive();
        const result = await port.execute(call, AbortSignal.any([callSignal, controller.signal]));
        // 即使最后一个 HTTP 回执已到达，窗口关闭后的迟到结果也不能进入下一轮模型。
        alive();
        return result;
      } };
    return {
      port: guardedPort, close,
      async get(value: Principal) {
        actor(value); await check();
        if (pending) await serviceCheck(pending.dispatch);
        return pending?.intent ?? null;
      },
      async take(value: Principal) {
        actor(value); await check();
        const session = current;
        if (!session) return null;
        await serviceCheck(session.dispatch);
        const exchange = await session.channel.take(value);
        alive();
        return exchange ? { exchange, intent: session.intent } : null;
      },
      async reply(value: Principal, input: unknown) {
        actor(value); await check();
        if (!current) throw new CloudError('inactive');
        await current.channel.reply(value, input);
        alive();
      },
      async decide(value: Principal, input: unknown) {
        actor(value); const decision = parseMcpTaskDecision(input); await check();
        const slot = pending;
        if (!slot || decision.invocationId !== slot.intent.invocationId || decision.intentDigest !== slot.intent.intentDigest ||
            decision.generation !== slot.intent.generation) throw new CloudError('conflict');
        const result = await decisions.decide(decision.invocationId, decision.intentDigest, decision.decision);
        alive();
        if (pending !== slot) throw new CloudError('inactive');
        slot.finish();
        return { invocationId: result.id, status: result.status };
      },
    };
  } catch (error) { close(); throw error; }
}

type Window = Awaited<ReturnType<typeof createNativeMcpTask>>;
export type McpTaskData = { confirmation: Awaited<ReturnType<Window['get']>> }
  | { delivery: Awaited<ReturnType<Window['take']>> }
  | { decision: Awaited<ReturnType<Window['decide']>> }
  | { accepted: true };
