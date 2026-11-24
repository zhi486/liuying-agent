import { CloudError, type CloudTaskView } from './cloud-task-contracts.ts';
import { MAX_TASK_EVENTS, TASK_EVENTS_VERSION, taskEventRound, type TaskEvent,
  type TaskEventBatch, type TaskEventPort } from './task-events.ts';

type Row = { device_id: string; created_at: number; seq: number; round: number; length: number; ended: number; phase: string };
type Storage = {
  sql: { exec<T extends Record<string, string | number | null | ArrayBuffer>>(
    query: string, ...values: (string | number | null)[]): { toArray(): T[]; one(): T } };
  transactionSync<T>(callback: () => T): T;
};

/** 复用当前owner的SQLite；只保留最近64个、24小时内的有界暂存流。 */
export class TaskEventStore {
  #storage: Storage;
  constructor(storage: Storage) {
    this.#storage = storage;
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS runtime_task_streams (
      task_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, created_at INTEGER NOT NULL,
      seq INTEGER NOT NULL, round INTEGER NOT NULL, length INTEGER NOT NULL, ended INTEGER NOT NULL, phase TEXT NOT NULL)`);
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS runtime_task_events (
      task_id TEXT NOT NULL, seq INTEGER NOT NULL, event_json TEXT NOT NULL, PRIMARY KEY(task_id,seq))`);
  }
  clear(taskId: string): void {
    this.#storage.transactionSync(() => {
      this.#storage.sql.exec('DELETE FROM runtime_task_events WHERE task_id=?', taskId);
      this.#storage.sql.exec('DELETE FROM runtime_task_streams WHERE task_id=?', taskId);
    });
  }
  start(taskId: string, deviceId: string): void {
    this.#storage.transactionSync(() => {
      this.#storage.sql.exec(`DELETE FROM runtime_task_streams WHERE created_at<? OR task_id IN
        (SELECT task_id FROM runtime_task_streams ORDER BY created_at DESC,task_id DESC LIMIT -1 OFFSET 63)`, Date.now() - 86400000);
      this.#storage.sql.exec('DELETE FROM runtime_task_events WHERE task_id NOT IN (SELECT task_id FROM runtime_task_streams)');
      this.#storage.sql.exec("INSERT INTO runtime_task_streams VALUES (?,?,?,0,0,0,0,'idle')", taskId, deviceId, Date.now());
    });
  }
  #row(taskId: string): Row {
    const row = this.#storage.sql.exec<Row>('SELECT device_id,created_at,seq,round,length,ended,phase FROM runtime_task_streams WHERE task_id=?', taskId).toArray()[0];
    if (!row) throw new CloudError('not_found');
    return row;
  }
  #append(taskId: string, row: Row, event: TaskEvent): void {
    if (row.seq >= MAX_TASK_EVENTS || row.ended) throw new CloudError('inactive');
    this.#storage.sql.exec('INSERT INTO runtime_task_events VALUES (?,?,?)', taskId, event.sequence, JSON.stringify(event));
    this.#storage.sql.exec('UPDATE runtime_task_streams SET seq=?,round=?,length=?,ended=?,phase=? WHERE task_id=?',
      event.sequence, event.round, event.kind === 'text_delta' ? row.length + event.text.length
        : event.kind === 'round_start' ? 0 : row.length,
      event.kind === 'task_end' ? 1 : 0,
      event.kind === 'round_start' ? 'open' : event.kind === 'round_end' ? 'closed' : row.phase, taskId);
  }
  port(taskId: string, active: (signal: AbortSignal) => void): TaskEventPort {
    const add = (requestId: string, signal: AbortSignal, part:
      { kind: 'round_start' } | { kind: 'text_delta'; text: string } |
      { kind: 'round_end'; outcome: 'answer' | 'tools' | 'discarded' }) => {
      active(signal);
      const round = taskEventRound(taskId, requestId);
      this.#storage.transactionSync(() => {
        const row = this.#row(taskId);
        if (part.kind === 'round_start' ? round !== row.round + 1 || row.phase === 'open'
          : round !== row.round || row.phase !== 'open')
          throw new CloudError('inactive');
        if (part.kind === 'text_delta' && (!part.text || part.text.length + row.length > 4000))
          throw new CloudError('unavailable');
        this.#append(taskId, row, { version: TASK_EVENTS_VERSION, taskId, sequence: row.seq + 1, round, ...part });
      });
    };
    return {
      begin: (id, signal) => add(id, signal, { kind: 'round_start' }),
      text: async (id, text, signal) => add(id, signal, { kind: 'text_delta', text }),
      end: (id, outcome, signal) => add(id, signal, { kind: 'round_end', outcome }),
    };
  }
  finish(task: CloudTaskView): void {
    this.#storage.transactionSync(() => {
      const row = this.#row(task.id);
      if (row.ended) return;
      this.#append(task.id, row, { version: TASK_EVENTS_VERSION, taskId: task.id,
        sequence: row.seq + 1, round: row.round, kind: 'task_end', status: task.status });
    });
  }
  read(task: CloudTaskView, deviceId: string, after: number): TaskEventBatch {
    const row = this.#row(task.id);
    if (row.device_id !== deviceId) throw new CloudError('unauthenticated');
    if (row.created_at + 86400000 <= Date.now()) {
      this.clear(task.id); throw new CloudError('request_expired');
    }
    if (['withdrawn', 'acknowledged', 'expired'].includes(task.resultState)) {
      this.clear(task.id); throw new CloudError('inactive');
    }
    if (after > row.seq) throw new CloudError('invalid_request');
    const events = this.#storage.sql.exec<{ event_json: string }>(
      'SELECT event_json FROM runtime_task_events WHERE task_id=? AND seq>? ORDER BY seq LIMIT 64', task.id, after)
      .toArray().map(item => JSON.parse(item.event_json) as TaskEvent);
    const cursor = events.at(-1)?.sequence ?? after;
    return { version: TASK_EVENTS_VERSION, taskId: task.id, cursor, terminal: !!row.ended && cursor === row.seq, events };
  }
}
