import { useCallback, useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import {
  agentTaskDetailSchema,
  agentTaskPageSchema,
  type AgentTaskDetail,
  type AgentTaskSummary,
} from '../../bridge/agentTasks';

type BridgeStatus = 'unavailable' | 'connecting' | 'ready' | 'error';
const PAGE_SIZE = 20;

export function useAgentTasks(bridgeStatus: BridgeStatus) {
  const epoch = useRef(0);
  const listSequence = useRef(0);
  const detailSequence = useRef(0);
  const mutationLock = useRef(false);
  const listLock = useRef(false);
  const lastSubmission = useRef<{ clientRequestId: string; prompt: string } | null>(null);
  const [page, setPage] = useState<{
    items: AgentTaskSummary[];
    total: number;
    nextOffset: number;
  } | null>(null);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AgentTaskDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const query = useCallback(
    async (offset: number, append = false) => {
      if (bridgeStatus !== 'ready' || (append && (listLock.current || mutationLock.current)))
        return;
      const generation = epoch.current;
      const sequence = ++listSequence.current;
      listLock.current = true;
      setLoading(true);
      setListError(null);
      try {
        const result = await sendNativeCommand('agent.list', { offset, limit: PAGE_SIZE });
        if (generation !== epoch.current || sequence !== listSequence.current) return;
        if (result.status === 'rejected') throw new Error();
        const parsed = agentTaskPageSchema.safeParse(result.data);
        if (!parsed.success || parsed.data.offset !== offset || parsed.data.limit !== PAGE_SIZE)
          throw new Error();
        const data = parsed.data;
        setPage((current) => ({
          items: append
            ? [
                ...new Map(
                  [...(current?.items ?? []), ...data.items].map((task) => [task.id, task]),
                ).values(),
              ]
            : data.items,
          total: data.totalItems,
          nextOffset: data.offset + data.items.length,
        }));
      } catch {
        if (generation === epoch.current && sequence === listSequence.current)
          setListError('读取本机任务失败，请重试');
      } finally {
        if (generation === epoch.current && sequence === listSequence.current) {
          listLock.current = false;
          setLoading(false);
        }
      }
    },
    [bridgeStatus],
  );

  useEffect(() => {
    epoch.current++;
    setPage(null);
    setSelectedId(null);
    setDetail(null);
    setDetailError(null);
    setMutationError(null);
    setPending(false);
    mutationLock.current = false;
    if (bridgeStatus === 'ready') void query(0);
    return () => {
      epoch.current += 1;
      listSequence.current += 1;
      detailSequence.current += 1;
      listLock.current = false;
    };
  }, [bridgeStatus, query]);

  function close() {
    detailSequence.current++;
    setSelectedId(null);
    setDetail(null);
    setDetailError(null);
  }

  async function open(taskId: string) {
    if (bridgeStatus !== 'ready' || mutationLock.current) return;
    const generation = epoch.current;
    const sequence = ++detailSequence.current;
    setSelectedId(taskId);
    setDetail(null);
    setDetailError(null);
    setMutationError(null);
    try {
      const result = await sendNativeCommand('agent.detail', { taskId });
      if (generation !== epoch.current || sequence !== detailSequence.current) return;
      if (result.status === 'rejected') throw new Error();
      const parsed = agentTaskDetailSchema.safeParse(result.data);
      if (!parsed.success || parsed.data.task.id !== taskId) throw new Error();
      setDetail(parsed.data);
    } catch {
      if (generation === epoch.current && sequence === detailSequence.current)
        setDetailError('读取任务详情失败，请重试');
    }
  }

  async function mutate(kind: 'save' | 'cancel', draft = ''): Promise<boolean> {
    if (bridgeStatus !== 'ready' || mutationLock.current) return false;
    const text = draft.trim();
    if (kind === 'save' && (!text || draft.length > 2000)) return false;
    if (kind === 'cancel' && (!detail || detail.task.status !== 'blocked')) return false;
    mutationLock.current = true;
    setPending(true);
    setMutationError(null);
    const generation = epoch.current;
    listSequence.current++;
    detailSequence.current++;
    if (kind === 'save') {
      setSelectedId(null);
      setDetail(null);
      setDetailError(null);
    }
    listLock.current = false;
    setLoading(false);
    try {
      if (kind === 'save' && lastSubmission.current?.prompt !== text) {
        lastSubmission.current = { clientRequestId: crypto.randomUUID(), prompt: text };
      }
      const result =
        kind === 'save'
          ? await sendNativeCommand('agent.submit', lastSubmission.current!)
          : await sendNativeCommand('agent.cancel', { taskId: detail!.task.id });
      if (generation !== epoch.current) return false;
      if (result.status === 'rejected') throw new Error();
      const parsed = agentTaskDetailSchema.safeParse(result.data);
      if (
        !parsed.success ||
        (kind === 'save'
          ? parsed.data.messages[0].text !== text
          : parsed.data.task.id !== detail!.task.id || parsed.data.task.status !== 'cancelled')
      )
        throw new Error();
      if (kind === 'save') lastSubmission.current = null;
      setSelectedId(parsed.data.task.id);
      setDetail(parsed.data);
      setDetailError(null);
      void query(0);
      return true;
    } catch {
      if (generation === epoch.current)
        setMutationError(
          kind === 'save'
            ? '保存结果未确认，草稿已保留。可重试，或刷新查看本机记录。'
            : '取消未确认，请重试或重新查看任务。',
        );
      return false;
    } finally {
      if (generation === epoch.current) {
        mutationLock.current = false;
        setPending(false);
      }
    }
  }

  return {
    page,
    loading,
    listError,
    selectedId,
    detail,
    detailError,
    mutationError,
    pending,
    refresh: () => void query(0),
    loadMore: () => void query(page?.nextOffset ?? 0, true),
    open: (id: string) => void open(id),
    close,
    save: (draft: string) => mutate('save', draft),
    cancel: () => void mutate('cancel'),
  };
}
