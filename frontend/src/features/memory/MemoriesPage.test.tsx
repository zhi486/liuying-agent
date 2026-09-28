import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { MemoriesPage } from './MemoriesPage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const id = '22222222-2222-4222-8222-222222222222';
const commandId = '33333333-3333-4333-8333-333333333333';
const memory = {
  id,
  content: '我喜欢夜跑',
  source: 'user_explicit',
  createdAtMillis: 1000,
  updatedAtMillis: 1000,
  version: 1,
};
const accepted = (data: unknown) => ({ status: 'accepted', data });
const page = (items: unknown[] = [], offset = 0, totalItems = items.length) => ({
  commandId,
  offset,
  limit: 20,
  totalItems,
  items,
});
const mount = () =>
  render(
    <MemoryRouter>
      <MemoriesPage />
    </MemoryRouter>,
  );

beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  send.mockResolvedValue(accepted(page()));
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open');
  };
});

it('未接原生时不展示假记忆且不发读写命令', () => {
  useBridgeStore.setState({ status: 'unavailable' });
  mount();
  expect(screen.getByRole('button', { name: '保存记忆' })).toBeDisabled();
  expect(send).not.toHaveBeenCalled();
  expect(screen.getByText(/尚未用于 Agent 回答/)).toBeVisible();
});

it('显式新增后核对回执再清空草稿，不发任何在线命令', async () => {
  send.mockImplementation(async (name, body) =>
    name === 'memory.create'
      ? accepted({ commandId, memory: { ...memory, id: body.memoryId, content: body.content } })
      : accepted(page()),
  );
  mount();
  await screen.findByText(/还没有记忆/);
  fireEvent.change(screen.getByLabelText('新增记忆'), { target: { value: ' 夜跑更开心 ' } });
  fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
  await waitFor(() => expect(screen.getByLabelText('新增记忆')).toHaveValue(''));
  expect(send.mock.calls.map(([name]) => name)).toEqual([
    'memory.list',
    'memory.create',
    'memory.list',
  ]);
  expect(send.mock.calls[1][1]).toEqual({ memoryId: expect.any(String), content: '夜跑更开心' });
});

it('超时后保留草稿并复用创建ID，连点不重复提交', async () => {
  let resolve!: (value: unknown) => void;
  send.mockResolvedValueOnce(accepted(page())).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  mount();
  await screen.findByText(/还没有记忆/);
  fireEvent.change(screen.getByLabelText('新增记忆'), { target: { value: '我喜欢夜跑' } });
  const save = screen.getByRole('button', { name: '保存记忆' });
  fireEvent.click(save);
  fireEvent.click(save);
  expect(send).toHaveBeenCalledTimes(2);
  await act(async () =>
    resolve({ status: 'rejected', error: { code: 'INTERNAL_ERROR', message: '失败' } }),
  );
  expect(screen.getByLabelText('新增记忆')).toHaveValue('我喜欢夜跑');
  send.mockResolvedValueOnce({ status: 'rejected', error: { code: 'INTERNAL_ERROR' } });
  fireEvent.click(save);
  await waitFor(() => expect(send).toHaveBeenCalledTimes(3));
  expect(send.mock.calls[2][1]).toEqual(send.mock.calls[1][1]);
});

it.each(['错误ID', '错误版本', '额外字段'])('新增回执%s时不假报保存成功', async (invalid) => {
  send.mockImplementation(async (name, body) => {
    if (name === 'memory.list') return accepted(page());
    const returned = {
      ...memory,
      id: invalid === '错误ID' ? id : body.memoryId,
      content: body.content,
      version: invalid === '错误版本' ? 2 : 1,
      ...(invalid === '额外字段' ? { secret: true } : {}),
    };
    return accepted({ commandId, memory: returned });
  });
  mount();
  await screen.findByText(/还没有记忆/);
  fireEvent.change(screen.getByLabelText('新增记忆'), { target: { value: '新偏好' } });
  fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
  await screen.findByRole('alert');
  expect(screen.getByLabelText('新增记忆')).toHaveValue('新偏好');
  expect(screen.queryByText('记忆已保存在本机。')).not.toBeInTheDocument();
});

it('编辑带原版本，冲突保留草稿并阻止翻页或新增导致草稿丢失', async () => {
  send
    .mockResolvedValueOnce(accepted(page([memory])))
    .mockResolvedValue({ status: 'rejected', error: { code: 'SESSION_CONFLICT' } });
  mount();
  await screen.findByText(memory.content);
  fireEvent.click(screen.getByRole('button', { name: `编辑记忆：${memory.content}` }));
  fireEvent.change(screen.getByLabelText('修改记忆'), { target: { value: '改为晨跑' } });
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
  await screen.findByText(/这条记忆已变化/);
  expect(send.mock.calls[1]).toEqual([
    'memory.update',
    { memoryId: id, version: 1, content: '改为晨跑' },
  ]);
  expect(screen.getByLabelText('修改记忆')).toHaveValue('改为晨跑');
  expect(screen.getByRole('button', { name: '刷新记忆' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '保存记忆' })).toBeDisabled();
});

it('确认删除前不发送，取消保留，确认成功后列表移除', async () => {
  send
    .mockResolvedValueOnce(accepted(page([memory])))
    .mockResolvedValueOnce(accepted({ commandId, memoryId: id, deleted: true }))
    .mockResolvedValue(accepted(page()));
  mount();
  await screen.findByText(memory.content);
  const remove = screen.getByRole('button', { name: `删除记忆：${memory.content}` });
  fireEvent.click(remove);
  expect(send).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('dialog').style.maxHeight).toBe(`${window.innerHeight - 32}px`);
  expect(screen.getByRole('dialog').style.getPropertyValue('--memory-content-height')).toBe(
    `${window.innerHeight * 0.3}px`,
  );
  fireEvent(window, new Event('resize'));
  expect(screen.getByRole('dialog').style.maxHeight).toBe(`${window.innerHeight - 32}px`);
  fireEvent.click(screen.getByRole('button', { name: '保留记忆' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledTimes(1);
  fireEvent.click(remove);
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '确认删除' }));
  await screen.findByText(/还没有记忆/);
  expect(send.mock.calls[1]).toEqual([
    'memory.delete',
    { memoryId: id, version: 1, confirmed: true },
  ]);
  expect(screen.queryByText(memory.content)).not.toBeInTheDocument();
});

it('迟到列表不能覆盖新增后的刷新结果', async () => {
  let resolve!: (value: unknown) => void;
  send
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    )
    .mockImplementationOnce(async (_name, body) =>
      accepted({ commandId, memory: { ...memory, id: body.memoryId } }),
    )
    .mockResolvedValue(accepted(page([memory])));
  mount();
  fireEvent.change(screen.getByLabelText('新增记忆'), { target: { value: memory.content } });
  fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
  await screen.findByText(memory.content);
  await act(async () => resolve(accepted(page())));
  expect(screen.getByText(memory.content)).toBeVisible();
});

it('断桥后的迟到成功回执不能清空草稿或显示成功', async () => {
  let resolve!: (value: unknown) => void;
  send.mockResolvedValueOnce(accepted(page())).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  mount();
  await screen.findByText(/还没有记忆/);
  fireEvent.change(screen.getByLabelText('新增记忆'), { target: { value: memory.content } });
  fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
  const memoryId = send.mock.calls[1][1].memoryId;
  act(() => useBridgeStore.setState({ status: 'error' }));
  await act(async () => resolve(accepted({ commandId, memory: { ...memory, id: memoryId } })));
  expect(screen.getByLabelText('新增记忆')).toHaveValue(memory.content);
  expect(screen.queryByText('记忆已保存在本机。')).not.toBeInTheDocument();
});

it('刷新未返回时不可编辑旧行，编辑中断桥及重连不藏起草稿', async () => {
  let resolve!: (value: unknown) => void;
  send
    .mockResolvedValueOnce(accepted(page([memory])))
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    )
    .mockResolvedValue(accepted(page()));
  mount();
  await screen.findByText(memory.content);
  fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
  expect(screen.getByRole('button', { name: `编辑记忆：${memory.content}` })).toBeDisabled();
  expect(screen.getByRole('button', { name: `删除记忆：${memory.content}` })).toBeDisabled();
  await act(async () => resolve(accepted(page([memory]))));
  fireEvent.click(screen.getByRole('button', { name: `编辑记忆：${memory.content}` }));
  fireEvent.change(screen.getByLabelText('修改记忆'), { target: { value: '还没保存的草稿' } });
  act(() => useBridgeStore.setState({ status: 'error' }));
  expect(screen.getByLabelText('修改记忆')).toHaveValue('还没保存的草稿');
  expect(screen.getByRole('button', { name: '取消编辑' })).toBeEnabled();
  act(() => useBridgeStore.setState({ status: 'ready' }));
  await screen.findByText(/还没有记忆/);
  expect(screen.getByLabelText('修改记忆')).toHaveValue('还没保存的草稿');
  fireEvent.click(screen.getByRole('button', { name: '取消编辑' }));
  expect(screen.queryByLabelText('修改记忆')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '刷新记忆' })).toBeEnabled();
});

it('翻页携带精确offset且刷新不追加旧列表', async () => {
  const items = Array.from({ length: 20 }, (_, index) => ({
    ...memory,
    id: `22222222-2222-4222-8222-${String(index).padStart(12, '0')}`,
    content: `偏好${index}`,
  }));
  send
    .mockResolvedValueOnce(accepted(page(items, 0, 21)))
    .mockResolvedValueOnce(accepted(page([memory], 20, 21)))
    .mockResolvedValue(accepted(page()));
  mount();
  await screen.findByText('偏好0');
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  await screen.findByText(memory.content);
  expect(send.mock.calls[1]).toEqual(['memory.list', { offset: 20, limit: 20 }]);
  expect(screen.queryByText('偏好0')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
  await screen.findByText(/还没有记忆/);
});
