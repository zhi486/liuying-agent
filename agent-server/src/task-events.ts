import { CloudError, uuid, type CloudTaskView } from './cloud-task-contracts.ts';

export const TASK_EVENTS_VERSION = 'task_events_v1';
export const MAX_TASK_EVENTS = 12016;
type EventBase = { version: typeof TASK_EVENTS_VERSION; taskId: string; sequence: number; round: number };
export type TaskEvent = EventBase & (
  | { kind: 'round_start' }
  | { kind: 'text_delta'; text: string }
  | { kind: 'round_end'; outcome: 'answer' | 'tools' | 'discarded' }
  | { kind: 'task_end'; status: CloudTaskView['status'] }
);
export type TaskEventBatch = { version: typeof TASK_EVENTS_VERSION; taskId: string;
  cursor: number; terminal: boolean; events: TaskEvent[] };
export interface TaskEventPort {
  begin(requestId: string, signal: AbortSignal): void;
  text(requestId: string, text: string, signal: AbortSignal): Promise<void>;
  end(requestId: string, outcome: 'answer' | 'tools' | 'discarded', signal: AbortSignal): void;
}

export function eventCursor(value: unknown): number {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,4})$/.test(value) || Number(value) > MAX_TASK_EVENTS)
    throw new CloudError('invalid_request');
  return Number(value);
}

/** SSE 仅传已保存的任务事件；订阅取消不等于取消任务，重连也不提交任务。 */
export function taskEventsResponse(load: (cursor: number) => Promise<TaskEventBatch>,
  after: number, signal: AbortSignal): Response {
  let cursor = after;
  let closed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let wake: (() => void) | undefined;
  let stop!: () => void;
  const finish = () => {
    closed = true;
    if (timer !== undefined) clearTimeout(timer);
    wake?.();
    signal.removeEventListener('abort', stop);
  };
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      stop = () => { if (!closed) { finish(); controller.close(); } };
      if (signal.aborted) stop(); else signal.addEventListener('abort', stop, { once: true });
    },
    async pull(controller) {
      if (closed) return;
      try {
        const batch = await load(cursor);
        if (closed) return;
        let text = '';
        for (const event of batch.events) {
          if (event.sequence !== cursor + 1) throw new CloudError('unavailable');
          cursor = event.sequence;
          text += `id: ${cursor}\nevent: ${event.kind}\ndata: ${JSON.stringify(event)}\n\n`;
        }
        if (text) controller.enqueue(encoder.encode(text));
        if (batch.terminal) { finish(); controller.close(); return; }
        // 批量读取消除逐字RPC；只暂停订阅，不阻塞模型或工具执行。
        await new Promise<void>(resolve => { wake = resolve; timer = setTimeout(resolve, 250); });
        wake = undefined;
        if (!closed && !text) controller.enqueue(encoder.encode(': waiting\n\n'));
      } catch {
        if (!closed) {
          controller.enqueue(encoder.encode('event: stream_error\ndata: {"error":"unavailable"}\n\n'));
          finish(); controller.close();
        }
      }
    },
    cancel() { finish(); },
  }, { highWaterMark: 0 });
  return new Response(stream, { headers: { 'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

export function taskEventRound(taskId: string, requestId: string): number {
  uuid(taskId);
  const round = Number(requestId.slice(taskId.length + 1));
  if (requestId !== `${taskId}:${round}` || !Number.isInteger(round) || round < 1 || round > 3)
    throw new CloudError('invalid_request');
  return round;
}
