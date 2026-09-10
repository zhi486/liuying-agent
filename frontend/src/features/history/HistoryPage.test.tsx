import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useBridgeStore } from '../../bridge/bridgeStore';
import type { HistoryQueryResult, HistoryRunRecord } from '../../bridge/contract';
import { HistoryPage } from './HistoryPage';

const sendNativeCommandMock = vi.hoisted(() => vi.fn());

vi.mock('../../bridge/bridgeStore', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../bridge/bridgeStore')>();
  return { ...actual, sendNativeCommand: sendNativeCommandMock };
});

describe('跑步记录', () => {
  beforeEach(() => {
    sendNativeCommandMock.mockReset();
    useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  });

  it('浏览器预览不展示虚构成绩', () => {
    render(<HistoryPage />);

    expect(screen.getByText('浏览器预览')).toBeVisible();
    expect(screen.getByText(/不会展示虚构成绩/)).toBeVisible();
    expect(screen.queryByText('5.02')).not.toBeInTheDocument();
    expect(sendNativeCommandMock).not.toHaveBeenCalled();
  });

  it('Bridge 就绪后查询并呈现真实记录', async () => {
    sendNativeCommandMock.mockResolvedValue({
      status: 'accepted',
      data: createResult(1, [latestRun, olderRun], 2, false),
    });
    useBridgeStore.setState({ status: 'ready', snapshot: null });

    render(<HistoryPage />);

    expect(screen.getByText('正在读取本地记录')).toBeVisible();
    expect(await screen.findByText('5.02')).toBeVisible();
    expect(screen.getByText('30:18')).toBeVisible();
    expect(screen.getByText('4.08 km')).toBeVisible();
    expect(screen.getByText('共 2 次')).toBeVisible();
    expect(screen.queryByText('本周计划')).not.toBeInTheDocument();
    expect(screen.queryByText('趋势洞察')).not.toBeInTheDocument();
    expect(sendNativeCommandMock).toHaveBeenCalledWith('history.query', {
      page: 1,
      pageSize: 10,
    });
  });

  it('真实查询为空时显示稳定空态', async () => {
    sendNativeCommandMock.mockResolvedValue({
      status: 'accepted',
      data: createResult(1, [], 0, false),
    });
    useBridgeStore.setState({ status: 'ready', snapshot: null });

    render(<HistoryPage />);

    expect(await screen.findByText('还没有跑步记录')).toBeVisible();
    expect(screen.getByText(/完成并保存第一次跑步后/)).toBeVisible();
  });

  it('查询被拒绝时呈现原因并可以重试', async () => {
    sendNativeCommandMock
      .mockResolvedValueOnce({
        status: 'rejected',
        error: { code: 'RESOURCE_UNAVAILABLE', message: '本地数据库暂不可用' },
      })
      .mockResolvedValueOnce({
        status: 'accepted',
        data: createResult(1, [], 0, false),
      });
    useBridgeStore.setState({ status: 'ready', snapshot: null });

    render(<HistoryPage />);

    expect(await screen.findByText('本地数据库暂不可用')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));

    expect(await screen.findByText('还没有跑步记录')).toBeVisible();
    expect(sendNativeCommandMock).toHaveBeenCalledTimes(2);
  });

  it('按原生分页继续加载并追加去重后的记录', async () => {
    sendNativeCommandMock
      .mockResolvedValueOnce({
        status: 'accepted',
        data: createResult(1, [latestRun], 2, true),
      })
      .mockResolvedValueOnce({
        status: 'accepted',
        data: createResult(2, [latestRun, olderRun], 2, false),
      });
    useBridgeStore.setState({ status: 'ready', snapshot: null });

    render(<HistoryPage />);

    fireEvent.click(await screen.findByRole('button', { name: '加载更多' }));

    expect(await screen.findByText('4.08 km')).toBeVisible();
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument();
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('history.query', {
        page: 2,
        pageSize: 10,
      }),
    );
  });

  it('拒绝格式不可信的历史数据', async () => {
    sendNativeCommandMock.mockResolvedValue({
      status: 'accepted',
      data: { page: 1, pageSize: 10, totalItems: 1, hasNextPage: false, items: [] },
    });
    useBridgeStore.setState({ status: 'ready', snapshot: null });

    render(<HistoryPage />);

    expect(await screen.findByText('历史记录数据格式无效')).toBeVisible();
  });
});

const latestRun: HistoryRunRecord = {
  id: 'run-latest',
  startedAt: '2026-09-01T11:14:00Z',
  endedAt: '2026-09-01T11:44:18Z',
  distanceMeters: 5020,
  durationSeconds: 1818,
  averagePaceSecPerKm: 362,
  averageHeartRate: 144,
  averageCadence: 172,
};

const olderRun: HistoryRunRecord = {
  id: 'run-older',
  startedAt: '2026-08-29T10:00:00Z',
  endedAt: '2026-08-29T10:25:42Z',
  distanceMeters: 4080,
  durationSeconds: 1542,
  averagePaceSecPerKm: null,
  averageHeartRate: null,
  averageCadence: null,
};

function createResult(
  page: number,
  items: HistoryRunRecord[],
  totalItems: number,
  hasNextPage: boolean,
): HistoryQueryResult {
  return {
    commandId: '06b8f5e1-6688-4494-abba-c50ba6baa0af',
    page,
    pageSize: 10,
    totalItems,
    hasNextPage,
    items,
  };
}
