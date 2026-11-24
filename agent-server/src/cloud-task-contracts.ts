export type CloudErrorCode =
  | 'unauthenticated'
  | 'unavailable'
  | 'invalid_request'
  | 'not_found'
  | 'conflict'
  | 'busy'
  | 'inactive'
  | 'request_expired';

export class CloudError extends Error {
  readonly code: CloudErrorCode;
  constructor(code: CloudErrorCode) {
    super(code);
    this.code = code;
  }
}

export async function cloudOperation<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    throw error instanceof CloudError ? error : new CloudError('unavailable');
  }
}

export function timestamp(now: () => number): number {
  const value = now();
  if (!Number.isSafeInteger(value) || value < 0 || value > Number.MAX_SAFE_INTEGER - 7776000000)
    throw new CloudError('unavailable');
  return value;
}

export function identifier(value: unknown): string {
  if (
    typeof value !== 'string' ||
    value.length < 1 ||
    value.length > 80 ||
    /[^a-zA-Z0-9_-]/.test(value)
  )
    throw new CloudError('invalid_request');
  return value;
}
import type { Action, AgentResult, AgentTask, StopReason } from './contracts.ts';
import { exactObject, hasMemoryContext, hasMemoryTools, hasMcpTools, parseTask } from './tools.ts';
import { hasKnowledge, parseKnowledgeCitations } from './knowledge-contracts.ts';
import { parseCostEstimate } from './cost-estimate.ts';
import { hasConversation } from './conversation.ts';

// 使用数据库执行时钟，不以等待 D1 前的应用时间快照放宽到期边界。
export const DATABASE_TIME_SQL =
  "(CAST(strftime('%s', 'now') AS INTEGER) * 1000 + CAST(substr(strftime('%f', 'now'), 4, 3) AS INTEGER))";

export type CloudTaskView = Readonly<{
  id: string;
  status: 'queued' | 'running' | AgentResult['status'];
  reason: string | null;
  acceptedAtMillis: number;
  deadlineAtMillis: number;
  updatedAtMillis: number;
  resultState: 'none' | 'available' | 'acknowledged' | 'expired' | 'withdrawn';
  result: AgentResult | null;
}>;

export function uuid(value: unknown): string {
  if (
    typeof value !== 'string' ||
    value.length !== 36 ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)
  )
    throw new CloudError('invalid_request');
  return value;
}

export function parseSubmission(value: unknown): { clientRequestId: string; task: AgentTask } {
  try {
    const object = exactObject(
      value,
      ['clientRequestId', 'localTaskId', 'prompt', 'consent', 'summaries', ...(hasMemoryContext(value) ? ['memoryContext'] : []), ...(hasKnowledge(value) ? ['knowledge'] : []), ...(hasConversation(value) ? ['conversation'] : []), ...(hasMemoryTools(value) ? ['memoryTools'] : []), ...(hasMcpTools(value) ? ['mcpTools'] : [])],
      'invalid_task',
    );
    const task = parseTask({
      taskId: uuid(object.localTaskId),
      prompt: object.prompt,
      consent: object.consent,
      summaries: object.summaries,
      ...(hasMemoryContext(object) ? { memoryContext: object.memoryContext } : {}),
      ...(hasKnowledge(object) ? { knowledge: object.knowledge } : {}),
      ...(hasConversation(object) ? { conversation: object.conversation } : {}),
      ...(hasMemoryTools(object) ? { memoryTools: object.memoryTools } : {}),
      ...(hasMcpTools(object) ? { mcpTools: object.mcpTools } : {}),
    });
    return { clientRequestId: uuid(object.clientRequestId), task };
  } catch {
    throw new CloudError('invalid_request');
  }
}

const BLOCKED = [
  'consent_required',
  'scope_denied',
  'duplicate_tool',
  'tool_limit',
  'step_limit',
  'budget_denied',
  'invalid_tool',
];
const FAILED = [
  'invalid_task',
  'invalid_model_output',
  'budget_unavailable',
  'model_unavailable',
  'usage_invalid',
  'usage_limit',
];

export function parseResult(value: unknown): AgentResult {
  try {
    const completed =
      value !== null &&
      typeof value === 'object' &&
      'status' in value &&
      value.status === 'completed';
    const object = exactObject(
      value,
      ['status', completed ? 'answer' : 'reason', 'actions', ...(completed && Object.hasOwn(value as object, 'sources') ? ['sources'] : []),
        ...(value !== null && typeof value === 'object' && Object.hasOwn(value, 'cost') ? ['cost'] : [])],
      'invalid_task',
    );
    const cost = Object.hasOwn(object, 'cost') ? { cost: parseCostEstimate(object.cost) } : {};
    if (!Array.isArray(object.actions) || object.actions.length > 9)
      throw new CloudError('invalid_request');
    let step = 0;
    let tools = 0;
    const actions: Action[] = object.actions.map((value: unknown) => {
      const model =
        value !== null &&
        typeof value === 'object' &&
        'kind' in value &&
        value.kind === 'model_requested';
      const action = exactObject(
        value,
        model ? ['kind', 'step'] : ['kind', 'step', 'tool'],
        'invalid_task',
      );
      if (model) {
        if (action.step !== step + 1 || step >= 3) throw new CloudError('invalid_request');
        step++;
        return { kind: 'model_requested', step };
      }
      if (
        action.kind !== 'tool_completed' ||
        step === 0 ||
        step === 3 ||
        action.step !== step ||
        ++tools > 6 ||
        (action.tool !== 'runs.list' && action.tool !== 'runs.summary' && action.tool !== 'memory.search' && action.tool !== 'memory.upsert' && action.tool !== 'mcp.call')
      )
        throw new CloudError('invalid_request');
      return { kind: 'tool_completed', step, tool: action.tool };
    });
    if (completed) {
      if (typeof object.answer !== 'string' || !object.answer.trim() || object.answer.length > 4000)
        throw new CloudError('invalid_request');
      return { status: 'completed', answer: object.answer.trim(), actions, ...cost,
        ...(Object.hasOwn(object, 'sources') ? { sources: parseKnowledgeCitations(object.sources) } : {}) };
    }
    const reason = object.reason;
    if (typeof reason !== 'string') throw new CloudError('invalid_request');
    const status =
      reason === 'cancelled'
        ? 'cancelled'
        : reason === 'deadline'
          ? 'timed_out'
          : BLOCKED.includes(reason)
            ? 'blocked'
            : FAILED.includes(reason)
              ? 'failed'
              : null;
    if (status === null || object.status !== status) throw new CloudError('invalid_request');
    return { status, reason: reason as StopReason, actions, ...cost };
  } catch {
    throw new CloudError('invalid_request');
  }
}
