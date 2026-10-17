import { CloudError } from './cloud-task-contracts.ts';

export function createRuntimeLifetime() {
  let end = performance.now() + 30000;
  let closed = false;
  let accepted = false;
  let failed = false;
  const pending = new Set<Promise<void>>();
  const unavailable = () => new CloudError('unavailable');
  const remaining = () => closed ? 0 : Math.max(0, end - performance.now());

  async function wait<T>(work: Promise<T>): Promise<T> {
    // 即使等待已截止，调用方交来的迟到拒绝也必须被消费。
    void work.catch(() => {});
    const ms = remaining();
    if (ms <= 0) { closed = true; throw unavailable(); }
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const result = await Promise.race([work, new Promise<never>((_, reject) => {
        timer = setTimeout(() => { closed = true; reject(unavailable()); }, ms);
      })]);
      if (remaining() <= 0) throw unavailable();
      return result;
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  }

  return {
    remaining,
    setRemaining(ms: number) {
      if (accepted || remaining() <= 0 || !Number.isFinite(ms) || ms <= 0 || ms > 30000)
        throw unavailable();
      accepted = true;
      end = performance.now() + ms;
    },
    wait,
    waitUntil(work: Promise<void>) {
      const observed = work.then(() => {}, () => { failed = true; });
      if (remaining() <= 0) return;
      pending.add(observed);
      void observed.then(() => { pending.delete(observed); });
    },
    async drain(): Promise<void> {
      while (pending.size > 0) await wait(Promise.all([...pending]));
      if (failed || remaining() <= 0) throw unavailable();
    },
    close() { closed = true; },
  };
}
