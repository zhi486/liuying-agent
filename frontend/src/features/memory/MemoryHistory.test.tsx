import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryHistory } from './MemoryHistory';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const id = '11111111-1111-4111-8111-111111111111';
const memory = {
  id,
  content: '当前偏好',
  source: 'user_explicit' as const,
  createdAtMillis: 1000,
  updatedAtMillis: 2000,
  version: 12,
};
const revisions = Array.from({ length: 12 }, (_, index) => ({
  version: 12 - index,
  content: `偏好${12 - index}`,
  changedAtMillis: 1000,
  changeSource: 'manual',
  turnId: null,
}));
const result = (offset = 0) => ({
  status: 'accepted',
  data: {
    commandId: id,
    memoryId: id,
    currentVersion: 12,
    offset,
    limit: 10,
    totalItems: 12,
    items: revisions.slice(offset, offset + 10),
  },
});
beforeEach(() => {
  send.mockReset();
  send.mockImplementation(async (_name, body) => result(body.offset));
});

it('只读分页且恢复按钮仅传目标版本，不直接写入', async () => {
  const restore = vi.fn();
  render(
    <MemoryHistory memory={memory} ready busy={false} onRestore={restore} onClose={vi.fn()} />,
  );
  await screen.findByText('偏好12');
  expect(screen.queryByRole('button', { name: '恢复版本 12' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '历史下一页' }));
  await screen.findByText('偏好1');
  expect(send.mock.calls[1]).toEqual(['memory.history', { memoryId: id, offset: 10, limit: 10 }]);
  fireEvent.click(screen.getByRole('button', { name: '恢复版本 1' }));
  expect(restore).toHaveBeenCalledWith(revisions[11]);
  expect(send).toHaveBeenCalledTimes(2);
});

it.each(['记忆ID', '当前版本', '分页'])('拒绝%s不对应的历史', async (invalid) => {
  const next = result();
  if (invalid === '记忆ID') next.data.memoryId = '22222222-2222-4222-8222-222222222222';
  if (invalid === '当前版本') next.data.currentVersion = 13;
  if (invalid === '分页') next.data.limit = 20;
  send.mockResolvedValue(next);
  render(
    <MemoryHistory memory={memory} ready busy={false} onRestore={vi.fn()} onClose={vi.fn()} />,
  );
  await screen.findByRole('alert');
  expect(screen.queryByText('偏好12')).not.toBeInTheDocument();
});

it('连接变化后不接受旧历史，忙碌时禁止恢复及翻页', async () => {
  let resolve!: (value: unknown) => void;
  send.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const props = { memory, onRestore: vi.fn(), onClose: vi.fn() };
  const view = render(<MemoryHistory {...props} ready busy={false} />);
  view.rerender(<MemoryHistory {...props} ready={false} busy={false} />);
  await act(async () => resolve(result()));
  expect(screen.queryByText('偏好12')).not.toBeInTheDocument();
  view.rerender(<MemoryHistory {...props} ready busy />);
  await screen.findByText('偏好12');
  expect(screen.getByRole('button', { name: '恢复版本 11' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '历史下一页' })).toBeDisabled();
});
