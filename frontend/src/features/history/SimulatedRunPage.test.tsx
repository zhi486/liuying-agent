import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { SimulatedRunPage } from './SimulatedRunPage';
import { simulatedRun, simulatedSamples } from './simulatedRun';
import { chartData } from './runMetricChart';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send, useBridgeStore: vi.fn() }));
beforeEach(() => {
  send.mockReset();
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
  };
});

it('模拟五秒序列覆盖六个区间，数据缺口不连线，总距离和心率一致', () => {
  expect(simulatedSamples).toHaveLength(392);
  const distance = simulatedSamples.reduce(
    (sum, sample) =>
      sum +
      (sample.averagePaceSecPerKm
        ? (sample.paceValidSeconds * 1000) / sample.averagePaceSecPerKm
        : 0),
    0,
  );
  expect(distance).toBeCloseTo(simulatedRun.record.distanceMeters, 6);
  for (const kind of ['pace', 'heartRate'] as const) {
    const data = chartData(simulatedSamples, kind);
    expect(data.validSeconds).toBe(simulatedRun.record.durationSeconds - 10);
    expect(data.zones.every((zone) => zone.seconds > 0)).toBe(true);
    expect(data.zones.reduce((sum, zone) => sum + zone.percent, 0)).toBe(100);
    expect(
      data.links.every((link) => data.values[link.from] !== null && data.values[link.to] !== null),
    ).toBe(true);
  }
  expect(chartData(simulatedSamples, 'heartRate').average).toBeCloseTo(
    simulatedRun.record.averageHeartRate!,
  );
  simulatedSamples.forEach((sample, i) => {
    expect(sample.startElapsedSeconds).toBe(i * 5);
    expect(sample.endElapsedSeconds).toBe((i + 1) * 5);
  });
});

it('预览与详情明确标注模拟，不能提交 AI，且不调用原生读写接口', async () => {
  render(
    <MemoryRouter>
      <SimulatedRunPage />
    </MemoryRouter>,
  );
  expect(screen.getByRole('heading', { name: '模拟跑步记录' })).toBeVisible();
  expect(screen.queryByRole('link', { name: '让流萤复盘本次跑步' })).not.toBeInTheDocument();
  for (const name of ['分段', '跑步配速区间', '跑步心率区间']) {
    fireEvent.click(await screen.findByRole('button', { name: `查看${name}详情` }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/模拟数据/)).toBeVisible();
    fireEvent.click(within(dialog).getByRole('button', { name: /关闭/ }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  }
  expect(send).not.toHaveBeenCalled();
});
