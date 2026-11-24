import { AgentError, type MemoryToolCall, type MemoryToolPort } from './contracts.ts';
import { CloudError, identifier, uuid } from './cloud-task-contracts.ts';
import { exactObject, validateToolCall } from './tools.ts';
import { parseMemoryObservation } from './memory-tools.ts';

export type NativeMemoryRequest = Readonly<{
  version: 'native_memory_v1'; requestId: string; taskId: string; generation: string; call: MemoryToolCall;
}>;
export type NativeMemoryReply = Readonly<{
  version: 'native_memory_v1'; generation: string; callId: string; observation: unknown;
}>;

export function parseNativeMemoryReply(value: unknown): NativeMemoryReply {
  try {
    const row = exactObject(value, ['version', 'generation', 'callId', 'observation'], 'invalid_tool');
    if (row.version !== 'native_memory_v1') throw new Error();
    return { version: 'native_memory_v1', generation: uuid(row.generation),
      callId: identifier(row.callId), observation: structuredClone(row.observation) };
  } catch { throw new CloudError('invalid_request'); }
}

/** 只供当前 DO 执行往返，不保存第二份记忆。重建后不恢复调用，不重跑模型。 */
export class NativeMemoryChannel implements MemoryToolPort {
  readonly requestId: string;
  readonly taskId: string;
  readonly generation: string;
  readonly deviceId: string;
  readonly factsAllowed: boolean;
  #closed = false;
  #pending: { call: MemoryToolCall; finish: (value: unknown) => void } | undefined;
  // 只在本轮存活期间确认重复回执；结束即清空，不作为跨进程权威存储。
  #replies = new Map<string, string>();
  #calls = new Set<string>();
  #waitMs: number;

  constructor(binding: { requestId: string; taskId: string; generation: string; deviceId: string; factsAllowed?: boolean },
    waitMs = 8000) {
    this.requestId = uuid(binding.requestId);
    this.taskId = uuid(binding.taskId);
    this.generation = uuid(binding.generation);
    this.deviceId = uuid(binding.deviceId);
    this.factsAllowed = binding.factsAllowed === true;
    this.#waitMs = waitMs;
  }

  async execute(input: MemoryToolCall, signal: AbortSignal): Promise<unknown> {
    signal.throwIfAborted();
    const call = validateToolCall(input);
    if (call.name !== 'memory.search' && call.name !== 'memory.upsert') throw new CloudError('invalid_request');
    if (call.name === 'memory.upsert' && call.args.category === 'fact' && !this.factsAllowed)
      throw new AgentError('scope_denied');
    if (this.#closed || this.#pending || this.#calls.has(call.id) || this.#calls.size >= 6)
      throw new CloudError('inactive');
    this.#calls.add(call.id);
    return new Promise(resolve => {
      let timer: ReturnType<typeof setTimeout>;
      const finish = (value: unknown) => {
        clearTimeout(timer);
        signal.removeEventListener('abort', abort);
        if (this.#pending?.call.id === call.id) this.#pending = undefined;
        resolve(value);
      };
      const abort = () => finish({ status: 'unconfirmed', reason: 'unavailable' });
      this.#pending = { call, finish };
      timer = setTimeout(abort, this.#waitMs);
      signal.addEventListener('abort', abort, { once: true });
      if (signal.aborted) abort();
    });
  }

  poll(): NativeMemoryRequest | null {
    if (this.#closed) throw new CloudError('inactive');
    return this.#pending ? { version: 'native_memory_v1', requestId: this.requestId, taskId: this.taskId,
      generation: this.generation, call: structuredClone(this.#pending.call) } : null;
  }

  reply(input: unknown): void {
    if (this.#closed) throw new CloudError('inactive');
    const reply = parseNativeMemoryReply(input);
    if (reply.generation !== this.generation) throw new CloudError('conflict');
    const pending = this.#pending;
    if (pending?.call.id !== reply.callId) {
      const previous = this.#replies.get(reply.callId);
      if (previous !== undefined && previous === JSON.stringify(reply.observation)) return;
      throw new CloudError(previous === undefined ? 'inactive' : 'conflict');
    }
    // 只接受固定回执结构；无效响应统一未知，绝不能把供应商错误原文送回模型。
    const observation = parseMemoryObservation(pending.call, reply.observation);
    this.#replies.set(reply.callId, JSON.stringify(reply.observation));
    pending.finish(observation);
  }

  close(): void {
    this.#closed = true;
    this.#pending?.finish({ status: 'unconfirmed', reason: 'unavailable' });
    this.#replies.clear();
    this.#calls.clear();
  }
}
