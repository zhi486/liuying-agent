import { AgentError } from './contracts.ts';

const fail = (): never => { throw new AgentError('model_unavailable'); };
const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : null;

/** 片段是暂存文本，不能作为已完成回答或工具执行依据；终态仍交由原有校验处理。 */
export async function readDeepseekStream(response: Response, signal: AbortSignal,
  onText: (text: string) => Promise<void>): Promise<unknown> {
  if (!response.ok || !response.body || signal.aborted ||
    response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !== 'text/event-stream') {
    void response.body?.cancel().catch(() => {});
    return fail();
  }
  const reader = response.body.getReader();
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false });
  let pending = '';
  let skipLF = false;
  let data = '';
  let bytes = 0;
  let id: unknown;
  let content = '';
  let finish: string | null = null;
  let usage: unknown;
  let done = false;
  const calls: { id: string; type: 'function'; function: { name: string; arguments: string } }[] = [];
  const dispatch = async () => {
    if (!data) return;
    const raw = data.slice(0, -1);
    data = '';
    if (done) return fail();
    if (raw === '[DONE]') {
      if (!finish) return fail();
      done = true;
      return;
    }
    if (finish) return fail();
    const value = object(JSON.parse(raw));
    if (!value || value.object !== 'chat.completion.chunk' || typeof value.id !== 'string' ||
      !value.id || value.id.length > 256 || (id !== undefined && value.id !== id) ||
      !Array.isArray(value.choices) || value.choices.length !== 1) return fail();
    id = value.id;
    const choice = object(value.choices[0]);
    const delta = object(choice?.delta);
    if (!choice || choice.index !== 0 || !delta ||
      (delta.role !== undefined && delta.role !== 'assistant') ||
      (delta.reasoning_content !== undefined && delta.reasoning_content !== null && delta.reasoning_content !== '') ||
      (delta.content !== undefined && delta.content !== null && typeof delta.content !== 'string') ||
      (choice.finish_reason !== null && typeof choice.finish_reason !== 'string')) return fail();
    if (delta.tool_calls !== undefined && delta.tool_calls !== null) {
      if (!Array.isArray(delta.tool_calls) || delta.tool_calls.length > 6) return fail();
      for (const item of delta.tool_calls) {
        const part = object(item);
        if (!part || !Number.isInteger(part.index) || typeof part.index !== 'number' ||
          part.index < 0 || part.index > calls.length || part.index >= 6) return fail();
        if (part.index === calls.length) {
          if (typeof part.id !== 'string' || !part.id || part.id.length > 256 || part.type !== 'function') return fail();
          calls.push({ id: part.id, type: 'function', function: { name: '', arguments: '' } });
        }
        const call = calls[part.index]!;
        const fn = object(part.function);
        if (!fn || (part.id !== undefined && part.id !== call.id) ||
          (part.type !== undefined && part.type !== 'function') ||
          (fn.name !== undefined && typeof fn.name !== 'string') ||
          (fn.arguments !== undefined && typeof fn.arguments !== 'string')) return fail();
        call.function.name += fn.name ?? '';
        call.function.arguments += fn.arguments ?? '';
        if (call.function.name.length > 128 || call.function.arguments.length > 65536) return fail();
      }
    }
    if (typeof delta.content === 'string' && delta.content) {
      content += delta.content;
      if (content.length > 4000) return fail();
      signal.throwIfAborted();
      await onText(delta.content);
      signal.throwIfAborted();
    }
    if (choice.finish_reason !== null) {
      if (!choice.finish_reason) return fail();
      finish = choice.finish_reason as string;
      usage = value.usage;
    } else if (value.usage !== undefined && value.usage !== null) return fail();
  };
  const line = async (text: string) => {
    if (text.length > 65536) return fail();
    if (!text) return dispatch();
    if (text === 'data' || text.startsWith('data:')) {
      const value = text === 'data' ? '' : text.slice(5).replace(/^ /, '');
      data += value + '\n';
      if (data.length > 65536) return fail();
    }
  };
  try {
    while (!done) {
      signal.throwIfAborted();
      const part = await reader.read();
      signal.throwIfAborted();
      bytes += part.value?.byteLength ?? 0;
      if (bytes > 2 * 1024 * 1024) return fail();
      pending += decoder.decode(part.value, { stream: !part.done });
      let start = 0;
      for (let i = 0; i < pending.length; i++) {
        const ch = pending[i];
        if (skipLF) {
          skipLF = false;
          if (ch === '\n') { start = i + 1; continue; }
        }
        if (ch !== '\r' && ch !== '\n') continue;
        await line(pending.slice(start, i));
        // CR 自身就是换行；只吞掉后续可选 LF，不等服务器关闭才处理终止事件。
        skipLF = ch === '\r';
        start = i + 1;
      }
      pending = pending.slice(start);
      if (pending.length > 65536) return fail();
      if (part.done && !done) return fail();
    }
    signal.throwIfAborted();
    if (pending.trim() || data) return fail();
    return { choices: [{ index: 0, finish_reason: finish,
      message: { role: 'assistant', content, ...(calls.length ? { tool_calls: calls } : {}) } }], usage };
  } finally {
    signal.removeEventListener('abort', cancel);
    cancel();
    reader.releaseLock();
  }
}
