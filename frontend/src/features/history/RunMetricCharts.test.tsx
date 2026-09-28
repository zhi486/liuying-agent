import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { RunMetricCharts } from './RunMetricCharts';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const record = { id: 'run-1', durationSeconds: 20, averagePaceSecPerKm: 360 };
const samples = [0, 5, 10, 15].map((start, i) => ({
  startElapsedSeconds: start,
  endElapsedSeconds: start + 5,
  segmentId: 0,
  averagePaceSecPerKm: 360 + i * 5,
  averageHeartRate: 140 + i * 10,
  paceValidSeconds: 5,
  heartRateValidSeconds: 5,
}));
const page = {
  commandId: '00000000-0000-4000-8000-000000000012',
  sessionId: 'run-1',
  offset: 0,
  totalItems: 4,
  samples,
};

beforeEach(() => {
  send.mockReset();
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open');
  };
});

it('展示两种曲线，详情可查看窗口数值并关闭', async () => {
  send.mockResolvedValue({ status: 'accepted', data: page });
  render(<RunMetricCharts record={record} />);
  fireEvent.click(await screen.findByRole('button', { name: '查看跑步心率区间详情' }));
  const dialog = screen.getByRole('dialog', { name: '跑步心率区间' });
  expect(within(dialog).getByText('心率区间用时分布')).toBeVisible();
  expect(within(dialog).getByText('有氧耐力')).toBeVisible();
  const selection = within(dialog).getByRole('slider', { name: '选择统计窗口' });
  fireEvent.change(selection, { target: { value: '1' } });
  expect(within(dialog).getByRole('status')).toHaveTextContent('00:05–00:10');
  expect(within(dialog).getByRole('status')).toHaveTextContent('150');
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭详情' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('旧记录不虚构曲线', async () => {
  send.mockResolvedValue({ status: 'accepted', data: { ...page, totalItems: 0, samples: [] } });
  render(<RunMetricCharts record={record} />);
  expect(await screen.findByText('本次记录未采集五秒序列，无法还原曲线。')).toBeVisible();
  expect(screen.queryByRole('slider')).not.toBeInTheDocument();
});

it('连续读取多页但页号异常时不展示不完整结果', async () => {
  send
    .mockResolvedValueOnce({ status: 'accepted', data: { ...page, samples: samples.slice(0, 2) } })
    .mockResolvedValueOnce({
      status: 'accepted',
      data: { ...page, offset: 0, samples: samples.slice(2) },
    });
  render(<RunMetricCharts record={record} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('曲线');
  expect(send).toHaveBeenLastCalledWith('history.series', {
    sessionId: 'run-1',
    offset: 2,
    limit: 300,
  });
  expect(screen.queryByRole('button', { name: '查看跑步心率区间详情' })).not.toBeInTheDocument();
});

it('完整分页按顺序显示最后一页，额外窗口不超过跑步用时', async () => {
  send
    .mockResolvedValueOnce({ status: 'accepted', data: { ...page, samples: samples.slice(0, 2) } })
    .mockResolvedValueOnce({
      status: 'accepted',
      data: { ...page, offset: 2, samples: samples.slice(2) },
    });
  render(<RunMetricCharts record={record} />);
  fireEvent.click(await screen.findByRole('button', { name: '查看跑步配速区间详情' }));
  const dialog = screen.getByRole('dialog');
  fireEvent.change(within(dialog).getByRole('slider'), { target: { value: '3' } });
  expect(within(dialog).getByRole('status')).toHaveTextContent('00:15–00:20');
});
