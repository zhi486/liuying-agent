import { useCallback, useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import {
  memoryContentSchema,
  memoryDeletedSchema,
  memoryDetailSchema,
  memoryPageSchema,
  type MemoryRecord,
} from '../../bridge/memories';

const PAGE_SIZE = 20;
type Change =
  | { kind: 'create'; content: string }
  | { kind: 'update'; memory: MemoryRecord; content: string }
  | { kind: 'delete'; memory: MemoryRecord };

export function useMemories(status: 'unavailable' | 'connecting' | 'ready' | 'error') {
  const epoch = useRef(0);
  const sequence = useRef(0);
  const locked = useRef(false);
  const retry = useRef<{ memoryId: string; content: string } | null>(null);
  const [page, setPage] = useState<{ items: MemoryRecord[]; total: number; offset: number } | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');

  const query = useCallback(
    async (offset: number) => {
      if (status !== 'ready') return;
      const generation = epoch.current;
      const request = ++sequence.current;
      setLoading(true);
      setError(null);
      try {
        const result = await sendNativeCommand('memory.list', { offset, limit: PAGE_SIZE });
        if (generation !== epoch.current || request !== sequence.current) return;
        if (result.status === 'rejected') throw new Error();
        const parsed = memoryPageSchema.safeParse(result.data);
        if (!parsed.success || parsed.data.offset !== offset || parsed.data.limit !== PAGE_SIZE)
          throw new Error();
        setPage({ items: parsed.data.items, total: parsed.data.totalItems, offset });
      } catch {
        if (generation === epoch.current && request === sequence.current)
          setError('读取记忆失败，请刷新重试。');
      } finally {
        if (generation === epoch.current && request === sequence.current) setLoading(false);
      }
    },
    [status],
  );

  useEffect(() => {
    epoch.current++;
    locked.current = false;
    setPage(null);
    setPending(false);
    setLoading(false);
    setError(null);
    setNotice('');
    if (status === 'ready') void query(0);
    return () => {
      epoch.current += 1;
      sequence.current += 1;
    };
  }, [status, query]);

  async function change(change: Change): Promise<boolean> {
    if (status !== 'ready' || locked.current) return false;
    if (change.kind !== 'delete' && !memoryContentSchema.safeParse(change.content).success)
      return false;
    const content = change.kind === 'delete' ? '' : change.content.trim();
    const generation = epoch.current;
    locked.current = true;
    sequence.current++;
    setLoading(false);
    setPending(true);
    setError(null);
    setNotice('');
    try {
      if (change.kind === 'create' && retry.current?.content !== content)
        retry.current = { memoryId: crypto.randomUUID(), content };
      const memoryId = change.kind === 'create' ? retry.current!.memoryId : change.memory.id;
      const result =
        change.kind === 'create'
          ? await sendNativeCommand('memory.create', retry.current!)
          : change.kind === 'update'
            ? await sendNativeCommand('memory.update', {
                memoryId,
                version: change.memory.version,
                content,
              })
            : await sendNativeCommand('memory.delete', {
                memoryId,
                version: change.memory.version,
                confirmed: true,
              });
      if (generation !== epoch.current) return false;
      if (result.status === 'rejected') {
        if (result.error.code === 'SESSION_CONFLICT') {
          setError('这条记忆已变化。请关闭编辑或删除确认，刷新后重新操作；草稿未清空。');
          return false;
        }
        throw new Error();
      }
      if (change.kind === 'delete') {
        const parsed = memoryDeletedSchema.safeParse(result.data);
        if (!parsed.success || parsed.data.memoryId !== memoryId) throw new Error();
      } else {
        const parsed = memoryDetailSchema.safeParse(result.data);
        const expectedVersion = change.kind === 'create' ? 1 : change.memory.version + 1;
        if (
          !parsed.success ||
          parsed.data.memory.id !== memoryId ||
          parsed.data.memory.content !== content ||
          parsed.data.memory.version !== expectedVersion
        )
          throw new Error();
      }
      if (change.kind === 'create') retry.current = null;
      setPage(null);
      setNotice(change.kind === 'delete' ? '已删除这条记忆。' : '记忆已保存在本机。');
      void query(0);
      return true;
    } catch {
      if (generation === epoch.current)
        setError('操作结果未确认，草稿已保留。可重试同一操作，或刷新核对。');
      return false;
    } finally {
      if (generation === epoch.current) {
        locked.current = false;
        setPending(false);
      }
    }
  }

  return {
    page,
    loading,
    pending,
    error,
    notice,
    change,
    refresh: () => {
      if (!locked.current) void query(0);
    },
    previous: () => {
      if (!locked.current) void query(Math.max(0, (page?.offset ?? 0) - PAGE_SIZE));
    },
    next: () => {
      if (!locked.current && page && page.offset + PAGE_SIZE < page.total)
        void query(page.offset + PAGE_SIZE);
    },
  };
}
