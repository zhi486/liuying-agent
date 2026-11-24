import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentPage } from './AgentPage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));

const commandId = '11111111-1111-4111-8111-111111111111';
const requestId = '22222222-2222-4222-8222-222222222222';
const accepted = (data: unknown) => ({ status: 'accepted', data });
const running = {
  requestId,
  prompt: '今晚怎么跑',
  busy: true,
  attempted: true,
  status: 'running',
  resultState: 'none',
  reason: null,
  answer: null,
  draftAnswer: '先按舒服的节奏开始。',
  pendingControl: null,
  confirmedControl: null,
  error: null,
  memoryRefs: [],
};
let item: Record<string, unknown>;
const listing = () => ({
  commandId,
  configured: true,
  configuring: false,
  origin: 'https://agent.example.com',
  items: [item],
});

beforeEach(() => {
  send.mockReset();
  item = { ...running };
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list') return accepted(listing());
    if (name === 'agent.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
    if (name === 'cloud.cancel') {
      item = { ...item, pendingControl: 'cancel', draftAnswer: undefined };
      return accepted(listing());
    }
    throw new Error(`合成测试禁止命令：${name}`);
  });
});

it('流式回答旁可直接停止生成，并显示请求待确认', async () => {
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
  await screen.findByText('先按舒服的节奏开始。');
  const stop = screen.getByRole('button', { name: '停止生成' });
  fireEvent.click(stop);
  await waitFor(() => expect(send).toHaveBeenCalledWith('cloud.cancel', { requestId }));
  expect(await screen.findByText(/正在停止生成；云端控制请求待确认/)).toBeVisible();
  expect(screen.queryByRole('button', { name: '停止生成' })).not.toBeInTheDocument();
  expect(send.mock.calls.some(([name]) => ['cloud.prepare', 'cloud.confirm'].includes(name))).toBe(
    false,
  );
});

it('停止后只可恢复原任务查询，不重新发送问题', async () => {
  item = { ...running, pendingControl: 'cancel', busy: false, draftAnswer: undefined };
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
  await screen.findByText(/正在停止生成；云端控制请求待确认/);
  expect(screen.queryByRole('button', { name: '停止生成' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '恢复查询' }));
  await waitFor(() => expect(send).toHaveBeenCalledWith('cloud.recover', { requestId }));
  expect(
    send.mock.calls.some(([name]) =>
      ['cloud.prepare', 'cloud.confirm', 'cloud.send'].includes(name),
    ),
  ).toBe(false);
});

it('云端确认取消后显示已取消，停止入口消失', async () => {
  item = {
    ...running,
    busy: false,
    status: 'cancelled',
    pendingControl: 'cancel',
    confirmedControl: 'cancel',
    draftAnswer: undefined,
  };
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
  await screen.findByText(/云端已确认控制请求/);
  expect(screen.queryByRole('button', { name: '停止生成' })).not.toBeInTheDocument();
  expect(screen.getByText('已取消')).toBeVisible();
  expect(screen.queryByText(/正在停止生成/)).not.toBeInTheDocument();
});

it('控制请求仍在通信时禁用恢复，不重复派发命令', async () => {
  item = { ...running, pendingControl: 'cancel', draftAnswer: undefined };
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
  await screen.findByText(/正在停止生成/);
  const recover = screen.getByRole('button', { name: '恢复查询' });
  expect(recover).toBeDisabled();
  fireEvent.click(recover);
  expect(send.mock.calls.some(([name]) => name === 'cloud.recover')).toBe(false);
});

it('停止命令未确认时说明恢复原任务，不重发问题或自动再次停止', async () => {
  const original = send.getMockImplementation()!;
  send.mockImplementation(async (name: string, ...args: unknown[]) => {
    if (name === 'cloud.cancel') throw new Error('合成连接中断');
    return original(name, ...args);
  });
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '停止生成' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('停止请求尚未确认');
  expect(screen.getByRole('alert')).toHaveTextContent('不要重新发送问题');
  expect(send.mock.calls.filter(([name]) => name === 'cloud.cancel')).toHaveLength(1);
  expect(
    send.mock.calls.some(([name]) =>
      ['cloud.prepare', 'cloud.confirm', 'cloud.send'].includes(name),
    ),
  ).toBe(false);
});
