import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { RunReviewReferenceSection } from './RunReviewReferenceSection';
const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
it('详情 B 切换分段，C 展开成绩，选择不改变正式页', () => {
  render(<RunReviewReferenceSection kind="detail" width="320" />);
  const b = within(screen.getByRole('article', { name: 'B 数据手册' }));
  fireEvent.click(b.getByRole('button', { name: '每公里分段' }));
  expect(b.getByText('6:12')).toBeVisible();
  const c = within(screen.getByRole('article', { name: 'C 分段优先' }));
  fireEvent.click(c.getByRole('button', { name: '整体成绩 · 5.02 公里' }));
  expect(c.getByText('169 步/分')).toBeVisible();
  fireEvent.click(c.getByRole('button', { name: '选择 C 方案' }));
  expect(c.getByRole('button', { name: '当前候选，尚未接入' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  expect(send).not.toHaveBeenCalled();
});
it('长按中途取消不会结束，持续按住才进入确认', () => {
  vi.useFakeTimers();
  vi.stubGlobal('PointerEvent', MouseEvent);
  try {
    render(<RunReviewReferenceSection kind="controls" width="320" />);
    const card = within(screen.getByRole('article', { name: 'A 展开数据' }));
    const stop = card.getByRole('button', { name: '结束' });
    fireEvent.pointerDown(stop, { button: 0 });
    act(() => vi.advanceTimersByTime(600));
    fireEvent.pointerCancel(stop);
    act(() => vi.advanceTimersByTime(1200));
    expect(card.queryByRole('region', { name: '结束确认' })).not.toBeInTheDocument();
    fireEvent.pointerDown(stop, { button: 0 });
    act(() => vi.advanceTimersByTime(1200));
    expect(card.getByRole('region', { name: '结束确认' })).toBeVisible();
  } finally {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  }
});
it('图表 B 切换指标，空态不虚构曲线，C 可选择窗口', () => {
  render(<RunReviewReferenceSection kind="charts" width="393" />);
  const b = within(screen.getByRole('article', { name: 'B 单图聚焦' }));
  fireEvent.click(b.getByRole('button', { name: '心率' }));
  expect(b.getByRole('heading', { name: '跑步心率' })).toBeVisible();
  fireEvent.click(b.getByRole('button', { name: '切换无曲线状态' }));
  expect(b.getByRole('status')).toHaveTextContent('无法还原曲线');
  const c = within(screen.getByRole('article', { name: 'C 窗口审阅' }));
  fireEvent.change(c.getByRole('slider', { name: '选择统计窗口' }), { target: { value: '20' } });
  expect(c.getByRole('status')).toHaveTextContent('01:40');
  expect(send).not.toHaveBeenCalled();
});
it('三种结束确认均可取消并保留暂停，确认只影响演示', () => {
  render(<RunReviewReferenceSection kind="controls" width="320" />);
  for (const name of ['A 展开数据', 'B 紧凑数据', 'C 距离聚焦']) {
    const card = within(screen.getByRole('article', { name }));
    fireEvent.click(card.getByRole('button', { name: '结束' }));
    fireEvent.click(card.getByRole('button', { name: '继续休息' }));
    expect(card.getByText('已暂停')).toBeVisible();
    fireEvent.click(card.getByRole('button', { name: '继续跑' }));
    fireEvent.click(card.getByRole('button', { name: '暂停' }));
    fireEvent.click(card.getByRole('button', { name: '结束' }));
    fireEvent.click(card.getByRole('button', { name: '确认结束' }));
    expect(card.getByRole('status')).toHaveTextContent('未保存真实记录');
  }
  expect(send).not.toHaveBeenCalled();
});
