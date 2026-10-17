import { AgentError } from './contracts.ts';
import type { AgentTask, MemoryContext, RunSummary, StopReason, ToolCall } from './contracts.ts';
import { isSupportedText } from './plain-text.ts';
import { hasKnowledge, parseKnowledge } from './knowledge-contracts.ts';
import { hasConversation, parseConversation } from './conversation.ts';

export function exactObject(
  value: unknown,
  keys: readonly string[],
  code: StopReason,
): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new AgentError(code);
  const object = value as Record<string, unknown>;
  if (
    Object.keys(object).length !== keys.length ||
    keys.some((key) => !Object.hasOwn(object, key))
  ) {
    throw new AgentError(code);
  }
  return object;
}

function id(value: unknown, code: StopReason): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(value)) throw new AgentError(code);
  return value;
}

function metric(value: unknown, max: number, nullable = false): number | null {
  if (nullable && value === null) return null;
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < 0 ||
    value > max ||
    (nullable && value === 0)
  ) {
    throw new AgentError('invalid_task');
  }
  return value;
}

function parseSummary(value: unknown): RunSummary {
  const object = exactObject(
    value,
    ['runId', 'distanceMeters', 'durationSeconds', 'averagePaceSecPerKm', 'averageHeartRate'],
    'invalid_task',
  );
  const durationSeconds = metric(object.durationSeconds, 604800)!;
  if (durationSeconds === 0) throw new AgentError('invalid_task');
  return Object.freeze({
    runId: id(object.runId, 'invalid_task'),
    distanceMeters: metric(object.distanceMeters, 1000000)!,
    durationSeconds,
    averagePaceSecPerKm: metric(object.averagePaceSecPerKm, 86400, true),
    averageHeartRate: metric(object.averageHeartRate, 400, true),
  });
}

export function hasMemoryContext(value: unknown): boolean {
  return value !== null && typeof value === 'object' && Object.hasOwn(value, 'memoryContext');
}

function parseMemoryContext(value: unknown): MemoryContext {
  const context = exactObject(value, ['version', 'approved', 'items'], 'invalid_task');
  if (context.version !== 'selected_memories_v1') throw new AgentError('invalid_task');
  if (context.approved !== true) throw new AgentError('consent_required');
  if (!Array.isArray(context.items) || context.items.length < 1 || context.items.length > 5)
    throw new AgentError('invalid_task');
  const items = context.items.map((value: unknown) => {
    const memory = exactObject(value, ['id', 'version', 'content'], 'invalid_task');
    if (
      typeof memory.id !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(memory.id) ||
      typeof memory.version !== 'number' || !Number.isInteger(memory.version) ||
      memory.version < 1 || memory.version > 2_147_483_647 ||
      !isSupportedText(memory.content) || !memory.content.trim() ||
      memory.content.length > 500 || memory.content !== memory.content.trim()
    ) throw new AgentError('invalid_task');
    return Object.freeze({ id: memory.id, version: memory.version, content: memory.content });
  });
  if (new Set(items.map((item) => item.id)).size !== items.length) throw new AgentError('invalid_task');
  return Object.freeze({ version: 'selected_memories_v1', approved: true, items: Object.freeze(items) });
}

export function parseTask(value: unknown): AgentTask {
  const withMemory = hasMemoryContext(value);
  const object = exactObject(value, ['taskId', 'prompt', 'consent', 'summaries', ...(withMemory ? ['memoryContext'] : []), ...(hasKnowledge(value) ? ['knowledge'] : []), ...(hasConversation(value) ? ['conversation'] : [])], 'invalid_task');
  const consent = exactObject(object.consent, ['cloudProcessing', 'runSummaries'], 'invalid_task');
  if (consent.cloudProcessing !== true) throw new AgentError('consent_required');
  if (typeof consent.runSummaries !== 'boolean') throw new AgentError('invalid_task');
  if (!isSupportedText(object.prompt) || !object.prompt.trim() || object.prompt.length > 2000)
    throw new AgentError('invalid_task');
  if (!Array.isArray(object.summaries) || object.summaries.length > 20)
    throw new AgentError('invalid_task');
  if (!consent.runSummaries && object.summaries.length > 0)
    throw new AgentError('consent_required');
  const summaries = object.summaries.map(parseSummary);
  if (new Set(summaries.map((run) => run.runId)).size !== summaries.length)
    throw new AgentError('invalid_task');
  return Object.freeze({
    taskId: id(object.taskId, 'invalid_task'),
    prompt: object.prompt.trim(),
    consent: Object.freeze({ cloudProcessing: true as const, runSummaries: consent.runSummaries }),
    summaries: Object.freeze(summaries),
    // 旧任务不补空字段，保持既有请求指纹与重试兼容。
    ...(withMemory ? { memoryContext: parseMemoryContext(object.memoryContext) } : {}),
    ...(hasKnowledge(object) ? { knowledge: parseKnowledge(object.knowledge) } : {}),
    ...(hasConversation(object) ? { conversation: parseConversation(object.conversation) } : {}),
  });
}

export function validateToolCall(value: unknown): ToolCall {
  const object = exactObject(value, ['id', 'name', 'args'], 'invalid_tool');
  const callId = id(object.id, 'invalid_tool');
  if (object.name === 'runs.list') {
    exactObject(object.args, [], 'invalid_tool');
    return Object.freeze({ id: callId, name: object.name, args: Object.freeze({}) });
  }
  if (object.name === 'runs.summary') {
    const args = exactObject(object.args, ['runId'], 'invalid_tool');
    return Object.freeze({
      id: callId,
      name: object.name,
      args: Object.freeze({ runId: id(args.runId, 'invalid_tool') }),
    });
  }
  throw new AgentError('invalid_tool');
}

export function executeTool(call: ToolCall, task: AgentTask): unknown {
  if (!task.consent.runSummaries) throw new AgentError('scope_denied');
  if (call.name === 'runs.list') return { runs: task.summaries };
  const run = task.summaries.find((summary) => summary.runId === call.args.runId);
  if (!run) throw new AgentError('scope_denied');
  return { run };
}

export const TOOL_DEFINITIONS = [
  {
    name: 'runs.list',
    description: '读取当前用户明确授权的跑步摘要列表；无原始轨迹和连续心率。',
    parameters: { type: 'object', properties: {}, required: [], additionalProperties: false },
  },
  {
    name: 'runs.summary',
    description: '根据 runId 读取授权范围内的一条跑步摘要。',
    parameters: {
      type: 'object',
      properties: { runId: { type: 'string' } },
      required: ['runId'],
      additionalProperties: false,
    },
  },
] as const;
