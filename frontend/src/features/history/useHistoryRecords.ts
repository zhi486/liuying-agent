import { useCallback, useEffect, useRef, useState } from 'react';

import { sendNativeCommand } from '../../bridge/bridgeStore';
import type { HistoryQueryResult } from '../../bridge/contract';
import { historyQueryResultSchema } from '../../bridge/schemas';

const PAGE_SIZE = 10;

type BridgeStatus = 'unavailable' | 'connecting' | 'ready' | 'error';
type HistoryPhase = 'loading' | 'ready' | 'empty' | 'error';

interface HistoryRecordsState {
  phase: HistoryPhase;
  data: HistoryQueryResult | null;
  error: string | null;
  isLoadingMore: boolean;
  loadMoreError: string | null;
}

const initialState: HistoryRecordsState = {
  phase: 'loading',
  data: null,
  error: null,
  isLoadingMore: false,
  loadMoreError: null,
};

export function useHistoryRecords(bridgeStatus: BridgeStatus) {
  const requestSequence = useRef(0);
  const [state, setState] = useState<HistoryRecordsState>(initialState);

  const queryPage = useCallback(
    async (page: number, append: boolean) => {
      if (bridgeStatus !== 'ready') return;

      const sequence = ++requestSequence.current;
      setState((current) =>
        append ? { ...current, isLoadingMore: true, loadMoreError: null } : initialState,
      );

      try {
        const result = await sendNativeCommand('history.query', { page, pageSize: PAGE_SIZE });
        if (sequence !== requestSequence.current) return;
        if (result.status === 'rejected') throw new Error(result.error.message);

        const parsed = historyQueryResultSchema.safeParse(result.data);
        if (!parsed.success) throw new Error('历史记录数据格式无效');
        if (parsed.data.page !== page) throw new Error('历史记录页码不匹配');

        setState((current) => {
          const items = append
            ? mergeById(current.data?.items ?? [], parsed.data.items)
            : parsed.data.items;
          const data = { ...parsed.data, items };

          return {
            phase: data.totalItems === 0 ? 'empty' : 'ready',
            data,
            error: null,
            isLoadingMore: false,
            loadMoreError: null,
          };
        });
      } catch (error) {
        if (sequence !== requestSequence.current) return;
        const message = error instanceof Error ? error.message : '读取历史记录失败';
        setState((current) =>
          append
            ? { ...current, isLoadingMore: false, loadMoreError: message }
            : { ...initialState, phase: 'error', error: message },
        );
      }
    },
    [bridgeStatus],
  );

  useEffect(() => {
    if (bridgeStatus === 'ready') void queryPage(1, false);

    return () => {
      requestSequence.current += 1;
    };
  }, [bridgeStatus, queryPage]);

  return {
    ...state,
    retry: () => void queryPage(1, false),
    loadMore: () => void queryPage((state.data?.page ?? 0) + 1, true),
  };
}

function mergeById(current: HistoryQueryResult['items'], incoming: HistoryQueryResult['items']) {
  const records = new Map(current.map((record) => [record.id, record]));
  incoming.forEach((record) => records.set(record.id, record));
  return [...records.values()];
}
