import { AgentError } from './contracts.ts';

export type WaitUntil = (pending: Promise<void>) => void;

// 只给宿主保活副本；原链继续处理真实结果。保活成功不等于账务成功。
export function retainBackground(pending: Promise<unknown>, waitUntil?: WaitUntil): void {
  if (!waitUntil) return;
  const guarded = pending.then(
    () => undefined,
    () => undefined,
  );
  try {
    waitUntil(guarded);
  } catch {
    throw new AgentError('budget_unavailable');
  }
}
