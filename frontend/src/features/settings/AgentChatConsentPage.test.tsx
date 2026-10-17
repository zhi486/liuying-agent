import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentChatConsentPage } from './AgentChatConsentPage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const base = {
  commandId: '11111111-1111-4111-8111-111111111111',
  configured: true,
  configuring: false,
  origin: 'https://agent.example.com',
  items: [],
};
const identityToken = 'a'.repeat(64);
const current = { ...base, chatAuthorized: false, chatConsentContext: identityToken };
const accepted = (data: unknown) => ({ status: 'accepted', data });
beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
});

it('默认不勾选，显式同意后才能开启；关闭无需再次同意且不调用模型', async () => {
  send.mockImplementation(async (name: string, body: { enabled?: boolean }) =>
    accepted(name === 'cloud.list' ? current : { ...current, chatAuthorized: body.enabled }),
  );
  render(<AgentChatConsentPage />);
  const enable = await screen.findByRole('button', { name: '开启持续授权' });
  expect(enable).toBeDisabled();
  expect(screen.getByRole('checkbox')).not.toBeChecked();
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(enable);
  const disable = await screen.findByRole('button', { name: '关闭持续授权' });
  expect(send).toHaveBeenCalledWith('cloud.chatConsent', { identityToken, enabled: true });
  fireEvent.click(disable);
  expect(await screen.findByRole('checkbox')).not.toBeChecked();
  expect(send.mock.calls).toEqual([
    ['cloud.list', {}],
    ['cloud.chatConsent', { identityToken, enabled: true }],
    ['cloud.chatConsent', { identityToken, enabled: false }],
  ]);
});

it('旧版只读列表不暗中开启授权', async () => {
  send.mockResolvedValue(accepted(base));
  render(<AgentChatConsentPage />);
  await screen.findByText(/当前原生版本尚不支持持续授权/);
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledTimes(1);
});

it('重复点击只提交一次，身份变化的回执不伪报成功', async () => {
  let finish!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'cloud.list'
      ? Promise.resolve(accepted(current))
      : new Promise((resolve) => {
          finish = resolve;
        }),
  );
  render(<AgentChatConsentPage />);
  fireEvent.click(await screen.findByRole('checkbox'));
  const button = screen.getByRole('button', { name: '开启持续授权' });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(send.mock.calls.filter(([name]) => name === 'cloud.chatConsent')).toHaveLength(1);
  await act(async () =>
    finish(accepted({ ...current, chatAuthorized: true, chatConsentContext: 'b'.repeat(64) })),
  );
  expect(await screen.findByRole('alert')).toHaveTextContent('操作结果尚未确认');
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
});

it('断开后忽略迟到回执，重新连接重新读取而不重试授权', async () => {
  let finish!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'cloud.list'
      ? Promise.resolve(accepted(current))
      : new Promise((resolve) => {
          finish = resolve;
        }),
  );
  render(<AgentChatConsentPage />);
  fireEvent.click(await screen.findByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', { name: '开启持续授权' }));
  act(() => useBridgeStore.setState({ status: 'connecting' }));
  await act(async () => finish(accepted({ ...current, chatAuthorized: true })));
  expect(screen.getByRole('status')).toHaveTextContent('请在手机连接就绪后管理授权');
  act(() => useBridgeStore.setState({ status: 'ready' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '开启持续授权' })).toBeDisabled());
  expect(send.mock.calls.filter(([name]) => name === 'cloud.chatConsent')).toHaveLength(1);
});
