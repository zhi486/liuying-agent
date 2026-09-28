import { act, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../app/routes';
import { useBridgeStore } from '../../bridge/bridgeStore';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));

const detail = {
  commandId: '00000000-0000-4000-8000-000000000005',
  record: {
    id: 'run-1',
    startedAt: '2026-09-14T10:00:00Z',
    endedAt: '2026-09-14T10:12:00Z',
    distanceMeters: 2000,
    durationSeconds: 720,
    averagePaceSecPerKm: 360,
    averageHeartRate: null,
    averageCadence: 160,
  },
  splits: [
    {
      index: 1,
      endDistanceMeters: 1000,
      durationSeconds: 350,
      averagePaceSecPerKm: 350,
      averageHeartRate: null,
    },
    {
      index: 2,
      endDistanceMeters: 2000,
      durationSeconds: 370,
      averagePaceSecPerKm: 370,
      averageHeartRate: 145,
    },
  ],
};
const show = () =>
  render(
    <MemoryRouter initialEntries={['/history/run-1']}>
      <AppRoutes />
    </MemoryRouter>,
  );

beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
});

it('总结页读取本次五秒曲线并提供两种详情入口', async () => {
  send.mockImplementation(async (name: string) => ({
    status: 'accepted',
    data:
      name === 'history.detail'
        ? detail
        : {
            commandId: detail.commandId,
            sessionId: 'run-1',
            offset: 0,
            totalItems: 1,
            samples: [
              {
                startElapsedSeconds: 0,
                endElapsedSeconds: 5,
                segmentId: 0,
                averagePaceSecPerKm: 360,
                averageHeartRate: 145,
                paceValidSeconds: 5,
                heartRateValidSeconds: 5,
              },
            ],
          },
  }));
  show();
  expect(await screen.findByRole('button', { name: '查看跑步配速区间详情' })).toBeVisible();
  expect(screen.getByRole('button', { name: '查看跑步心率区间详情' })).toBeVisible();
  expect(send).toHaveBeenCalledWith('history.series', {
    sessionId: 'run-1',
    offset: 0,
    limit: 300,
  });
});

it('按记录标识展示真实概要和分段，缺失心率不归零', async () => {
  send.mockResolvedValue({ status: 'accepted', data: detail });
  show();
  expect(await screen.findByRole('heading', { name: '跑后总结' })).toBeVisible();
  expect(await screen.findByText('2.00', { selector: 'strong' })).toBeVisible();
  expect(screen.getByRole('table', { name: '每公里分段' })).toHaveTextContent('5:50');
  expect(screen.getAllByText('未采集').length).toBeGreaterThan(0);
  expect(screen.getByRole('heading', { name: '每公里平均配速' })).toBeVisible();
  expect(send).toHaveBeenCalledWith('history.detail', { sessionId: 'run-1' });
  expect(screen.queryByText(/千卡|会员|广告|最大摄氧量/)).not.toBeInTheDocument();
});

it('旧版不支持详情时提示升级且可以重试', async () => {
  send
    .mockResolvedValueOnce({
      status: 'rejected',
      error: { code: 'INVALID_MESSAGE', message: '未知命令' },
    })
    .mockResolvedValueOnce({ status: 'accepted', data: { ...detail, splits: [] } });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('更新');
  fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
  expect(await screen.findByText('未保存分段数据')).toBeVisible();
});

it('不呈现与当前标识不符的记录', async () => {
  send.mockResolvedValue({
    status: 'accepted',
    data: { ...detail, record: { ...detail.record, id: 'other' } },
  });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('不匹配');
  expect(screen.queryByText('2.00')).not.toBeInTheDocument();
});

it('浏览器未连接时不查询也不显示虚构成绩', async () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  show();
  expect(await screen.findByText(/请在流萤 App 内查看/)).toBeVisible();
  expect(send).not.toHaveBeenCalled();
});

it('切换记录后丢弃前一记录的迟到响应', async () => {
  let finishFirst!: (value: unknown) => void;
  send
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishFirst = resolve;
        }),
    )
    .mockResolvedValueOnce({
      status: 'accepted',
      data: { ...detail, record: { ...detail.record, id: 'run-2', distanceMeters: 3000 } },
    });
  const router = createMemoryRouter([{ path: '*', element: <AppRoutes /> }], {
    initialEntries: ['/history/run-1'],
  });
  render(<RouterProvider router={router} />);
  await screen.findByText('正在读取本次总结…');
  await act(async () => {
    await router.navigate('/history/run-2');
  });
  expect(await screen.findByText('3.00', { selector: 'strong' })).toBeVisible();
  await act(async () => {
    finishFirst({ status: 'accepted', data: detail });
  });
  expect(screen.getByText('3.00', { selector: 'strong' })).toBeVisible();
  expect(screen.queryByText('2.00', { selector: 'strong' })).not.toBeInTheDocument();
});
