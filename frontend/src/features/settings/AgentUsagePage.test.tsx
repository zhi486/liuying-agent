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
  render(<AgentUsagePage />);
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
  render(<AgentUsagePage />);
  await screen.findByText('暂无可展示的用量记录。');
  expect(screen.queryByText(row.prompt)).not.toBeInTheDocument();
  expect(screen.queryByText('待确认')).not.toBeInTheDocument();
});

it('失败提供只读重试，不伪装成零费用或空账本', async () => {
  send
    .mockRejectedValueOnce(new Error('合成读取失败'))
    .mockResolvedValue({ status: 'accepted', data: listing });
  render(<AgentUsagePage />);
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
  render(<AgentUsagePage />);
  expect(screen.getByText('正在读取本机用量…')).toBeVisible();
  act(() => useBridgeStore.setState({ status: 'connecting', snapshot: null }));
  await act(async () => resolve({ status: 'accepted', data: { ...listing, items: [row] } }));
  expect(screen.queryByText(row.prompt)).not.toBeInTheDocument();
  expect(screen.getByText('请在手机连接就绪后查看本机用量。')).toBeVisible();
});

it('原生未连接时无命令，用户须知不伪称自主记忆和插件已开放', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  render(
    <>
      <AgentUsagePage />
      <AgentNoticesPage />
    </>,
  );
  expect(screen.getByText(/自主记忆与 MCP 插件尚未开放/)).toBeVisible();
  expect(send).not.toHaveBeenCalled();
});
