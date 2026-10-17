import { isSupportedText } from '../plain-text.ts';
import { MODEL_TOKEN_LIMITS } from '../model-limits.ts';
import { TOOL_DEFINITIONS } from '../tools.ts';

type ToolName = 'runs_list' | 'runs_summary';
export type WireCall = {
  id: string;
  type: 'function';
  function: { name: ToolName; arguments: string };
};
export type WireMessage =
  | { role: 'system' | 'user' | 'assistant'; content: string }
  | { role: 'assistant'; content: null; tool_calls: WireCall[] }
  | { role: 'tool'; tool_call_id: string; content: string };
export type WireTool = {
  type: 'function';
  function: { name: ToolName; description: string; parameters: Record<string, unknown> };
};
export type CountingRequest = {
  model: 'deepseek-flash';
  thinking: { type: 'disabled' };
  stream: false;
  max_tokens: number;
  messages: WireMessage[];
  tools: WireTool[];
};

function requireValue(condition: unknown): asserts condition {
  if (!condition) throw new Error('输入计数不可用');
}

function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value));
  requireValue(Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)));
  return value as Record<string, unknown>;
}

function canonicalJson(raw: string): unknown {
  const parsed: unknown = JSON.parse(raw);
  requireValue(JSON.stringify(parsed) === raw);
  return parsed;
}

// 只比较两种固定 schema 的值和字段集合；保留实际请求的字段次序供模板使用。
function sameSchema(actual: unknown, expected: unknown): boolean {
  if (expected === null || typeof expected !== 'object') return actual === expected;
  if (actual === null || typeof actual !== 'object') return false;
  if (Array.isArray(expected))
    return Array.isArray(actual) && actual.length === expected.length &&
      expected.every((item, index) => sameSchema(actual[index], item));
  if (Array.isArray(actual)) return false;
  const left = actual as Record<string, unknown>;
  return Object.keys(left).length === Object.keys(expected).length &&
    Object.entries(expected).every(([key, item]) => Object.hasOwn(left, key) && sameSchema(left[key], item));
}

export function parseCountingRequest(raw: string): CountingRequest {
  try {
    requireValue(typeof raw === 'string' && new TextEncoder().encode(raw).length <= 65536);
    const request = object(canonicalJson(raw), ['model', 'thinking', 'stream', 'max_tokens', 'messages', 'tools']);
    requireValue(request.model === 'deepseek-flash' && request.stream === false);
    requireValue(object(request.thinking, ['type']).type === 'disabled');
    requireValue(typeof request.max_tokens === 'number' && Number.isInteger(request.max_tokens) &&
      request.max_tokens >= 1 && request.max_tokens <= MODEL_TOKEN_LIMITS.maxOutputTokens);
    requireValue(Array.isArray(request.tools) && request.tools.length <= 2);
    const names = new Set<string>();
    for (const value of request.tools) {
      const tool = object(value, ['type', 'function']);
      requireValue(tool.type === 'function');
      const fn = object(tool.function, ['name', 'description', 'parameters']);
      requireValue(typeof fn.name === 'string' && !names.has(fn.name));
      const definition = TOOL_DEFINITIONS.find(item => item.name.replace('.', '_') === fn.name);
      requireValue(definition && isSupportedText(fn.description) && sameSchema(fn.parameters, definition.parameters));
      names.add(fn.name);
    }
    requireValue(Array.isArray(request.messages) && request.messages.length >= 2);
    const first = request.messages[0];
    const second = request.messages[1];
    requireValue(first?.role === 'system' && second?.role === 'user');
    const pending = new Set<string>();
    const seen = new Set<string>();
    for (const [index, value] of request.messages.entries()) {
      requireValue(value !== null && typeof value === 'object' && !Array.isArray(value));
      if (value.role === 'system' || value.role === 'user') {
        const message = object(value, ['role', 'content']);
        requireValue(!pending.size && (message.role !== 'system' || index === 0) && isSupportedText(message.content));
      } else if (value.role === 'assistant') {
        if (typeof value.content === 'string') {
          const message = object(value, ['role', 'content']);
          requireValue(!pending.size && isSupportedText(message.content));
          continue;
        }
        const message = object(value, ['role', 'content', 'tool_calls']);
        requireValue(!pending.size && message.content === null && Array.isArray(message.tool_calls) &&
          message.tool_calls.length >= 1 && message.tool_calls.length <= 6);
        for (const item of message.tool_calls) {
          const call = object(item, ['id', 'type', 'function']);
          requireValue(isSupportedText(call.id) && call.id.length > 0 && !seen.has(call.id) && call.type === 'function');
          const fn = object(call.function, ['name', 'arguments']);
          requireValue(typeof fn.name === 'string' && names.has(fn.name) && typeof fn.arguments === 'string');
          const args = object(canonicalJson(fn.arguments), fn.name === 'runs_list' ? [] : ['runId']);
          if (fn.name === 'runs_summary') requireValue(isSupportedText(args.runId));
          seen.add(call.id);
          pending.add(call.id);
        }
      } else {
        const message = object(value, ['role', 'tool_call_id', 'content']);
        requireValue(message.role === 'tool' && typeof message.tool_call_id === 'string' &&
          pending.delete(message.tool_call_id) && isSupportedText(message.content));
      }
    }
    const last = request.messages[request.messages.length - 1];
    requireValue(!pending.size && (last.role === 'user' || last.role === 'tool'));
    return request as CountingRequest;
  } catch {
    throw new Error('输入计数不可用');
  }
}
