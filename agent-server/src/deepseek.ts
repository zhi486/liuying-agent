import { AgentError } from './contracts.ts';
import { retainBackground, type WaitUntil } from './background.ts';
import type { ModelDecision, ModelPort, Usage } from './contracts.ts';
import { validateToolCall } from './tools.ts';

const wireNames = {
  'runs.list': 'runs_list',
  'runs.summary': 'runs_summary',
} as const;
const unavailable = () => new AgentError('model_unavailable');
const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

function normalizeUsage(value: unknown): Usage | null {
  const usage = object(value);
  if (
    !usage ||
    !integer(usage.prompt_tokens) ||
    !integer(usage.completion_tokens) ||
    !integer(usage.total_tokens) ||
    usage.prompt_tokens + usage.completion_tokens !== usage.total_tokens
  )
    return null;
  const hit = usage.prompt_cache_hit_tokens;
  const miss = usage.prompt_cache_miss_tokens;
  if (
    (hit !== undefined || miss !== undefined) &&
    (!integer(hit) || !integer(miss) || hit + miss !== usage.prompt_tokens)
  )
    return null;
  if (usage.prompt_tokens_details !== undefined) {
    const cached = object(usage.prompt_tokens_details)?.cached_tokens;
    if (!integer(cached) || cached > usage.prompt_tokens || (hit !== undefined && hit !== cached))
      return null;
  }
  return {
    inputTokens: usage.prompt_tokens,
    outputTokens: usage.completion_tokens,
  };
}

function normalizeDecision(data: Record<string, unknown>): ModelDecision | null {
  if (!Array.isArray(data.choices) || data.choices.length !== 1) return null;
  const choice = object(data.choices[0]);
  const message = object(choice?.message);
  if (
    choice?.index !== 0 ||
    message?.role !== 'assistant' ||
    (message.reasoning_content !== undefined &&
      message.reasoning_content !== null &&
      message.reasoning_content !== '')
  )
    return null;
  if (choice.finish_reason === 'stop') {
    if (
      typeof message.content !== 'string' ||
      !message.content.trim() ||
      message.content.length > 4000 ||
      (message.tool_calls !== undefined &&
        message.tool_calls !== null &&
        (!Array.isArray(message.tool_calls) || message.tool_calls.length !== 0))
    )
      return null;
    return { kind: 'final', text: message.content.trim() };
  }
  if (
    choice.finish_reason !== 'tool_calls' ||
    !Array.isArray(message.tool_calls) ||
    message.tool_calls.length < 1 ||
    message.tool_calls.length > 6 ||
    (message.content !== null && message.content !== undefined && message.content !== '')
  )
    return null;
  try {
    const calls = message.tool_calls.map((value) => {
      const call = object(value);
      const fn = object(call?.function);
      if (call?.type !== 'function' || typeof fn?.arguments !== 'string') throw unavailable();
      const name =
        fn.name === 'runs_list' ? 'runs.list' : fn.name === 'runs_summary' ? 'runs.summary' : null;
      return validateToolCall({
        id: call.id,
        name,
        args: JSON.parse(fn.arguments),
      });
    });
    if (new Set(calls.map((call) => call.id)).size !== calls.length) return null;
    return { kind: 'tools', calls };
  } catch {
    return null;
  }
}

async function readResponse(response: Response, signal: AbortSignal): Promise<unknown> {
  if (!response.ok || !response.body || signal.aborted) {
    void response.body?.cancel().catch(() => {});
    throw unavailable();
  }
  const reader = response.body.getReader();
  const cancel = () => {
    void reader.cancel().catch(() => {});
  };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    const chunks: Uint8Array[] = [];
    let length = 0;
    while (true) {
      signal.throwIfAborted();
      const chunk = await reader.read();
      if (chunk.done) break;
      length += chunk.value.byteLength;
      if (length > 128 * 1024) {
        cancel();
        throw unavailable();
      }
      chunks.push(chunk.value);
    }
    signal.throwIfAborted();
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
  } finally {
    signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}

export function createDeepseekModel(options: {
  apiKey: string;
  countInputTokens: (serializedRequest: string) => Promise<number>;
  fetch?: typeof globalThis.fetch;
  beforeDispatch?: (requestId: string, signal: AbortSignal) => Promise<void>;
  onUsage?: (requestId: string, usage: Usage) => Promise<void>;
  waitUntil?: WaitUntil;
}): ModelPort {
  const fetchRequest = options.fetch ?? globalThis.fetch;
  const apiKey = options.apiKey;
  const countInputTokens = options.countInputTokens;
  const beforeDispatch = options.beforeDispatch;
  const onUsage = options.onUsage;
  const waitUntil = options.waitUntil;
  if (
    typeof apiKey !== 'string' ||
    !apiKey.trim() ||
    /[\x00-\x20\x7f]/.test(apiKey) ||
    typeof countInputTokens !== 'function'
  )
    throw unavailable();
  return {
    async generate(request, signal) {
      const controller = new AbortController();
      const abort = () => controller.abort();
      signal.addEventListener('abort', abort, { once: true });
      if (signal.aborted) abort();
      const timer = setTimeout(abort, 30000);
      let rejectAbort!: () => void;
      const cancelled = new Promise<never>((_, reject) => {
        rejectAbort = () => reject(unavailable());
        controller.signal.addEventListener('abort', rejectAbort, {
          once: true,
        });
        if (controller.signal.aborted) rejectAbort();
      });
      // 宿主注册可能在 race 前抛出；预先取消的拒绝也必须被消费。
      void cancelled.catch(() => {});
      try {
        const operation = async () => {
          controller.signal.throwIfAborted();
          const { maxInputTokens, maxOutputTokens } = request.limits;
          if (
            !integer(maxInputTokens) ||
            maxInputTokens < 1 ||
            maxInputTokens > 20000 ||
            !integer(maxOutputTokens) ||
            maxOutputTokens < 1 ||
            maxOutputTokens > 600
          )
            throw unavailable();
          const body = JSON.stringify({
            model: 'deepseek-flash',
            thinking: { type: 'disabled' },
            stream: false,
            max_tokens: maxOutputTokens,
            messages: [
              { role: 'system', content: request.instructions },
              ...request.messages.map((message) => {
                if (message.role === 'user') return { role: 'user', content: message.text };
                if (message.role === 'tool')
                  return {
                    role: 'tool',
                    tool_call_id: message.callId,
                    content: JSON.stringify(message.data),
                  };
                return {
                  role: 'assistant',
                  content: null,
                  tool_calls: message.calls.map((call) => ({
                    id: call.id,
                    type: 'function',
                    function: {
                      name: wireNames[call.name],
                      arguments: JSON.stringify(call.args),
                    },
                  })),
                };
              }),
            ],
            tools: request.tools.map((tool) => ({
              type: 'function',
              function: { ...tool, name: wireNames[tool.name] },
            })),
          });
          if (new TextEncoder().encode(body).length > 64 * 1024) throw unavailable();
          // 计数器必须由可信服务提供，覆盖实际模型模板；禁止用字符估算作为硬预算依据。
          const inputTokens = await countInputTokens(body);
          controller.signal.throwIfAborted();
          if (!integer(inputTokens) || inputTokens < 1 || inputTokens > maxInputTokens)
            throw unavailable();
          await beforeDispatch?.(request.requestId, controller.signal);
          controller.signal.throwIfAborted();
          const response = await fetchRequest('https://api.deepseek.com/chat/completions', {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${apiKey}`,
              'Content-Type': 'application/json',
            },
            body,
            signal: controller.signal,
            redirect: 'error',
          });
          const data = object(await readResponse(response, controller.signal));
          const usage = normalizeUsage(data?.usage);
          // 已验证的费用与任务结果权限分开：取消等待不取消已开始的收据持久化。
          // 正常路径必须等收据成功，失败不能把 usage 交给核心绕过这一步。
          if (usage) await onUsage?.(request.requestId, usage);
          return {
            decision: data ? normalizeDecision(data) : null,
            usage,
          };
        };
        const pending = operation();
        retainBackground(pending, waitUntil);
        return await Promise.race([pending, cancelled]);
      } catch {
        throw unavailable();
      } finally {
        clearTimeout(timer);
        signal.removeEventListener('abort', abort);
        controller.signal.removeEventListener('abort', rejectAbort);
        controller.abort();
      }
    },
  };
}
