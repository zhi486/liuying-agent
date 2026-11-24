import { MemoryRouter } from 'react-router-dom';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentUsagePage } from './AgentUsagePage';
import { AgentNoticesPage } from './AgentNoticesPage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const id = '11111111-1111-4111-8111-111111111111';
const listing = {
  commandId: id,
  configured: true,
  configuring: false,
  origin: 'https://agent.example.com',
  items: [],
};
const row = {
  requestId: id,
  prompt: '不应重新显示的内容',
  busy: false,
  attempted: true,
  status: 'completed',
  resultState: 'acknowledged',
  reason: null,
  answer: '旧回答',
  pendingControl: null,
  confirmedControl: null,
  error: null,
  memoryRefs: [],
};
beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
});

it('空状态明确范围，刷新只读本机而不恢复或重发', async () => {
  send.mockResolvedValue({ status: 'accepted', data: listing });
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  expect(await screen.findByText('暂无可展示的用量记录。')).toBeVisible();
  expect(screen.getByText(/当前会话最近 10 条本机记录/)).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '刷新本机用量' }));
  await screen.findByText('暂无可展示的用量记录。');
  expect(send.mock.calls).toEqual([
    ['cloud.list', {}],
    ['cloud.list', {}],
  ]);
});

it.each([
  { pendingControl: 'withdraw' },
  { resultState: 'withdrawn' },
  { confirmedControl: 'withdraw' },
])('不重新展示撤回内容和费用：%o', async (withdrawal) => {
  send.mockResolvedValue({
    status: 'accepted',
    data: { ...listing, items: [{ ...row, ...withdrawal }] },
  });
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  await screen.findByText('暂无可展示的用量记录。');
  expect(screen.queryByText(row.prompt)).not.toBeInTheDocument();
  expect(screen.queryByText('待确认')).not.toBeInTheDocument();
});

it('失败提供只读重试，不伪装成零费用或空账本', async () => {
  send
    .mockRejectedValueOnce(new Error('合成读取失败'))
    .mockResolvedValue({ status: 'accepted', data: listing });
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法读取本机用量');
  expect(screen.queryByText('暂无可展示的用量记录。')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
  await screen.findByText('暂无可展示的用量记录。');
  expect(send.mock.calls.every(([name]) => name === 'cloud.list')).toBe(true);
});

it('读取中断后不展示迟到的旧记录', async () => {
  let resolve!: (value: unknown) => void;
  send.mockReturnValue(
    new Promise((done) => {
      resolve = done;
    }),
  );
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  expect(screen.getByText('正在读取本机用量…')).toBeVisible();
  act(() => useBridgeStore.setState({ status: 'connecting', snapshot: null }));
  await act(async () => resolve({ status: 'accepted', data: { ...listing, items: [row] } }));
  expect(screen.queryByText(row.prompt)).not.toBeInTheDocument();
  expect(screen.getByText('请在手机连接就绪后查看本机用量。')).toBeVisible();
});

it('原生未连接时无命令，用户须知明确记忆与插件分开授权', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  render(
    <MemoryRouter>
      <AgentUsagePage />
      <AgentNoticesPage />
    </MemoryRouter>,
  );
  expect(screen.getByText(/自动记忆默认关闭/)).toBeVisible();
  expect(screen.getByText(/MCP 插件需登记服务/)).toBeVisible();
  expect(screen.getByText(/删除对话后最多保留 100 条必要用量/)).toBeVisible();
  expect(screen.getByText(/预算超出不关闭功能/)).toBeVisible();
  expect(send).not.toHaveBeenCalled();
});

it('B 视图只汇总当前会话已知估算，切换展开不触发额外命令，保留删除后回执', async () => {
  const cost = {
    currency: 'USD',
    estimatedMicros: 1420,
    inputTokens: 1240,
    outputTokens: 386,
    usageComplete: true,
    rateStatus: 'reference',
  };
  send.mockResolvedValue({
    status: 'accepted',
    data: {
      ...listing,
      items: [
        { ...row, prompt: '合成轻松跑', cost },
        {
          ...row,
          requestId: '22222222-2222-4222-8222-222222222222',
          prompt: '待确认回执',
          cost: { ...cost, estimatedMicros: null, usageComplete: false, rateStatus: 'stale' },
        },
      ],
      retainedUsage: [
        {
          requestId: '33333333-3333-4333-8333-333333333333',
          atMillis: 1000,
          cost: { ...cost, estimatedMicros: 9000 },
        },
      ],
    },
  });
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  const summary = await screen.findByRole('region', { name: '当前会话已知费用小计' });
  expect(summary).toHaveTextContent('$0.001420');
  expect(summary).toHaveTextContent('1 笔有估算 · 1 笔待确认');
  expect(screen.getByText('删除后保留的必要用量', { selector: 'h2' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: 'Token 用量' }));
  expect(screen.getByRole('button', { name: 'Token 用量' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  const record = screen.getByRole('button', { name: /合成轻松跑/ });
  expect(record).toHaveTextContent('1,626');
  fireEvent.click(record);
  expect(record).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByText('使用内置参考费率，不是实时价格。')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: /待确认回执/ }));
  expect(screen.getByText('部分用量待确认，不代表零费用。')).toBeVisible();
  expect(screen.getByText('沿用上次核验费率，可能已过时。')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '估算费用' }));
  expect(screen.getByRole('button', { name: /已删除对话的用量/ })).toHaveTextContent('$0.009000');
  expect(send.mock.calls).toEqual([['cloud.list', {}]]);
});
