import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryAuthorization } from './MemoryAuthorization';
import { memoryAuthorizationSchema } from '../../bridge/cloudAgent';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const commandId = '11111111-1111-4111-8111-111111111111';
const state = (enabled = false, context = 'a'.repeat(64)) => ({
  commandId,
  configured: true,
  enabled,
  context,
});
const accepted = (data: unknown) => ({ status: 'accepted', data });

it('事实范围单独勾选；关闭事实仍保留基础授权，关闭基础同时撤销事实', async () => {
  send
    .mockResolvedValueOnce(accepted({ ...state(true), factsAllowed: false }))
    .mockResolvedValueOnce(accepted({ ...state(true, 'b'.repeat(64)), factsAllowed: true }))
    .mockResolvedValueOnce(accepted({ ...state(true, 'c'.repeat(64)), factsAllowed: false }))
    .mockResolvedValueOnce(accepted({ ...state(false, 'd'.repeat(64)), factsAllowed: false }));
  render(<MemoryAuthorization ready />);
  const checkbox = await screen.findByRole('checkbox', {
    name: '我另行允许上述事实写入与发送范围',
  });
  expect(checkbox).not.toBeChecked();
  expect(screen.getByRole('switch', { name: '事实写入' })).toBeDisabled();
  expect(send).toHaveBeenCalledTimes(1);
  fireEvent.click(checkbox);
  fireEvent.click(screen.getByRole('switch', { name: '事实写入' }));
  await screen.findByText('事实写入已允许');
  expect(send.mock.calls[1]).toEqual([
    'cloud.memoryConsent',
    { identityToken: 'a'.repeat(64), enabled: true, factsAllowed: true },
  ]);
  fireEvent.click(screen.getByRole('switch', { name: '事实写入' }));
  await screen.findByText('事实写入未允许');
  expect(screen.getByText('自动记忆已开启')).toBeVisible();
  expect(send.mock.calls[2]).toEqual([
    'cloud.memoryConsent',
    { identityToken: 'b'.repeat(64), enabled: true, factsAllowed: false },
  ]);
  fireEvent.click(screen.getByRole('switch', { name: '自动记忆' }));
  await screen.findByText('自动记忆未开启');
  expect(send.mock.calls[3]).toEqual([
    'cloud.memoryConsent',
    { identityToken: 'c'.repeat(64), enabled: false, factsAllowed: false },
  ]);
  expect(screen.queryByText('事实写入已允许')).not.toBeInTheDocument();
});

it.each([undefined, false])(
  '事实授权回执缺失或不匹配（%s）不视为启用，不自动重发',
  async (factsAllowed) => {
    send
      .mockResolvedValueOnce(accepted({ ...state(true), factsAllowed: false }))
      .mockResolvedValueOnce(
        accepted({
          ...state(true, 'b'.repeat(64)),
          ...(factsAllowed === undefined ? {} : { factsAllowed }),
        }),
      );
    render(<MemoryAuthorization ready />);
    fireEvent.click(await screen.findByRole('checkbox'));
    fireEvent.click(screen.getByRole('switch', { name: '事实写入' }));
    await screen.findByRole('alert');
    expect(screen.queryByText('事实写入已允许')).not.toBeInTheDocument();
    expect(send).toHaveBeenCalledTimes(2);
  },
);
beforeEach(() => {
  send.mockReset();
  send.mockResolvedValue(accepted(state()));
});

it('未连接时不读取或写入；连接后默认未勾选且不自动授权', async () => {
  const view = render(<MemoryAuthorization ready={false} />);
  expect(send).not.toHaveBeenCalled();
  view.rerender(<MemoryAuthorization ready />);
  await screen.findByText('自动记忆未开启');
  expect(screen.getByRole('checkbox')).not.toBeChecked();
  expect(screen.getByRole('switch', { name: '自动记忆' })).toBeDisabled();
  expect(send.mock.calls).toEqual([['cloud.memoryAuthorization', {}]]);
});

it('显式勾选后启用，核对新授权代次；关闭使用最新令牌且不删除记忆', async () => {
  send
    .mockResolvedValueOnce(accepted(state()))
    .mockResolvedValueOnce(accepted(state(true, 'b'.repeat(64))))
    .mockResolvedValueOnce(accepted(state(false, 'c'.repeat(64))));
  render(<MemoryAuthorization ready />);
  await screen.findByRole('checkbox');
  fireEvent.click(screen.getByRole('checkbox'));
  expect(send).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('switch', { name: '自动记忆' }));
  await screen.findByText('自动记忆已开启');
  expect(send.mock.calls[1]).toEqual([
    'cloud.memoryConsent',
    { identityToken: 'a'.repeat(64), enabled: true },
  ]);
  fireEvent.click(screen.getByRole('switch', { name: '自动记忆' }));
  await screen.findByText('自动记忆未开启');
  expect(send.mock.calls[2]).toEqual([
    'cloud.memoryConsent',
    { identityToken: 'b'.repeat(64), enabled: false },
  ]);
  expect(screen.getByRole('checkbox')).not.toBeChecked();
});

it.each(['无新代次', '错误状态', '未知字段', '拒绝'])(
  '授权回执%s时状态待核对，不重发',
  async (invalid) => {
    const next = state(
      invalid !== '错误状态',
      invalid === '无新代次' ? 'a'.repeat(64) : 'b'.repeat(64),
    );
    send
      .mockResolvedValueOnce(accepted(state()))
      .mockResolvedValueOnce(
        invalid === '拒绝'
          ? { status: 'rejected' }
          : accepted({ ...next, ...(invalid === '未知字段' ? { extra: true } : {}) }),
      );
    render(<MemoryAuthorization ready />);
    await screen.findByRole('checkbox');
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('switch', { name: '自动记忆' }));
    await screen.findByRole('alert');
    expect(screen.queryByText('自动记忆已开启')).not.toBeInTheDocument();
    expect(send).toHaveBeenCalledTimes(2);
    send.mockResolvedValueOnce(accepted(state()));
    fireEvent.click(screen.getByRole('button', { name: '重新读取自动记忆状态' }));
    await screen.findByRole('checkbox');
    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(send.mock.calls[2]).toEqual(['cloud.memoryAuthorization', {}]);
  },
);

it('连点仅发送一次，断桥后的迟到成功不得开启界面状态', async () => {
  let resolve!: (value: unknown) => void;
  send.mockResolvedValueOnce(accepted(state())).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const view = render(<MemoryAuthorization ready />);
  await screen.findByRole('checkbox');
  fireEvent.click(screen.getByRole('checkbox'));
  const button = screen.getByRole('switch', { name: '自动记忆' });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(send).toHaveBeenCalledTimes(2);
  view.rerender(<MemoryAuthorization ready={false} />);
  await act(async () => resolve(accepted(state(true, 'b'.repeat(64)))));
  expect(screen.queryByText('自动记忆已开启')).not.toBeInTheDocument();
  view.rerender(<MemoryAuthorization ready />);
  await waitFor(() => expect(send).toHaveBeenCalledTimes(3));
});

it('未配置和不可读状态不提供启用按钮；协议拒绝伪造身份组合', async () => {
  send.mockResolvedValueOnce(
    accepted({ commandId, configured: false, enabled: false, context: null }),
  );
  render(<MemoryAuthorization ready />);
  await screen.findByText('请先在设置中配置在线 Agent。');
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  for (const value of [
    { ...state(), context: null },
    { ...state(), configured: false },
    { commandId, configured: false, enabled: true, context: null },
    { ...state(), context: 'bad' },
  ])
    expect(memoryAuthorizationSchema.safeParse(value).success).toBe(false);
});
