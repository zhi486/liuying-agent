import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { AgentMemoryPreview } from './AgentMemoryPreview';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));

const commandId = '33333333-3333-4333-8333-333333333333';
it('翻页清空选择，不把前一页记忆偷偷保留到下一页', async () => {
  const many = Array.from({ length: 21 }, (_, index) => ({
    ...items[0],
    id: `11111111-1111-4111-8111-${index.toString().padStart(12, '0')}`,
    content: `偏好${index}`,
  }));
  send.mockImplementation(async (_name: string, body: { offset: number }) =>
    accepted({
      commandId,
      offset: body.offset,
      limit: 20,
      totalItems: 21,
      items: many.slice(body.offset, body.offset + 20),
    }),
  );
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  pickFirst();
  fireEvent.click(screen.getByRole('button', { name: '下一页记忆' }));
  await screen.findByLabelText(/偏好20/);
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
  expect(screen.getByRole('button', { name: '核对本次记忆' })).toBeDisabled();
});

it('核对未完成就收起重开，旧回包不恢复预览或选择', async () => {
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  pickFirst();
  let finish!: (value: unknown) => void;
  send.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
  fireEvent.click(screen.getByRole('button', { name: '收起记忆选择' }));
  await open();
  await act(async () => finish(accepted(preview())));
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
  expect(screen.queryByRole('region', { name: '已核对的记忆预览' })).not.toBeInTheDocument();
});

const items = Array.from({ length: 6 }, (_, index) => ({
  id: `11111111-1111-4111-8111-11111111111${index}`,
  version: 1,
  content: `偏好${index}`,
  source: 'user_explicit',
  createdAtMillis: 1000,
  updatedAtMillis: 1000,
}));
const accepted = (data: unknown) => ({ status: 'accepted', data });
const page = (records = items) => ({
  commandId,
  offset: 0,
  limit: 20,
  totalItems: records.length,
  items: records,
});
const preview = (records = items.slice(0, 1)) => ({
  commandId,
  scope: 'local_preview',
  items: records,
});
async function open() {
  fireEvent.click(screen.getByRole('button', { name: '选择本次记忆' }));
  await screen.findByLabelText(/偏好0/);
}
function pickFirst() {
  fireEvent.click(screen.getByLabelText(/偏好0/));
}

beforeEach(() => {
  send.mockReset();
  send.mockImplementation(async (name: string) =>
    accepted(name === 'memory.list' ? page() : preview()),
  );
});

it('不展开不读取，默认不选择且无上传授权按钮', async () => {
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  expect(send).not.toHaveBeenCalled();
  await open();
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
  expect(screen.getByRole('button', { name: '核对本次记忆' })).toBeDisabled();
  expect(screen.getByText(/选择和预览不会上传/)).toBeVisible();
  expect(send.mock.calls.map(([name]) => name)).toEqual(['memory.list']);
});

it('最多选择五条，取消一条后可选择另一条', async () => {
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  for (let index = 0; index < 5; index++)
    fireEvent.click(screen.getByLabelText(new RegExp(`偏好${index}`)));
  expect(screen.getByLabelText(/偏好5/)).toBeDisabled();
  expect(screen.getByText('已选择 5 / 5 条')).toBeVisible();
  pickFirst();
  expect(screen.getByLabelText(/偏好5/)).toBeEnabled();
  fireEvent.click(screen.getByLabelText(/偏好5/));
  expect(screen.getByText('已选择 5 / 5 条')).toBeVisible();
});

it('预览仅发ID和版本，核对返回内容后明确尚未发送', async () => {
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  pickFirst();
  fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
  expect(await screen.findByRole('region', { name: '已核对的记忆预览' })).toHaveTextContent(
    '偏好0',
  );
  expect(screen.getByText('本机核对通过 · 尚未发送')).toBeVisible();
  expect(send).toHaveBeenLastCalledWith('memory.preview', {
    memoryRefs: [{ id: items[0].id, version: 1 }],
  });
  expect(send.mock.calls.every(([name]) => ['memory.list', 'memory.preview'].includes(name))).toBe(
    true,
  );
});

it.each(['version', 'content', 'id', 'scope', 'count', 'rejected'])(
  '%s 异常不把返回数据当成成功预览',
  async (fault) => {
    render(<AgentMemoryPreview status="ready" prompt="问题" />);
    await open();
    pickFirst();
    const record = {
      ...items[0],
      ...(fault === 'version'
        ? { version: 2 }
        : fault === 'content'
          ? { content: '未见过的新正文' }
          : fault === 'id'
            ? { id: items[1].id }
            : {}),
    };
    send.mockResolvedValueOnce(
      fault === 'rejected'
        ? { status: 'rejected', error: { code: 'SESSION_CONFLICT' } }
        : accepted({
            ...preview(fault === 'count' ? [record, items[1]] : [record]),
            ...(fault === 'scope' ? { scope: 'cloud_approved' } : {}),
          }),
    );
    fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('没有上传');
    expect(screen.queryByRole('region', { name: '已核对的记忆预览' })).not.toBeInTheDocument();
  },
);

it('连点只查一次，问题变化使迟到预览失效', async () => {
  const view = render(<AgentMemoryPreview status="ready" prompt="原问题" />);
  await open();
  pickFirst();
  let finish!: (value: unknown) => void;
  send.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  const button = screen.getByRole('button', { name: '核对本次记忆' });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(send.mock.calls.filter(([name]) => name === 'memory.preview')).toHaveLength(1);
  view.rerender(<AgentMemoryPreview status="ready" prompt="新问题" />);
  await act(async () => finish(accepted(preview())));
  expect(screen.queryByRole('region', { name: '已核对的记忆预览' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '核对本次记忆' })).toBeEnabled();
});

it('断桥清空选择，迟到回包不能恢复旧预览', async () => {
  const view = render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  pickFirst();
  let finish!: (value: unknown) => void;
  send.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
  view.rerender(<AgentMemoryPreview status="error" prompt="问题" />);
  await act(async () => finish(accepted(preview())));
  expect(screen.getByText('请在手机 App 内选择记忆。')).toBeVisible();
  view.rerender(<AgentMemoryPreview status="ready" prompt="问题" />);
  await screen.findByLabelText(/偏好0/);
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
  expect(screen.queryByRole('region', { name: '已核对的记忆预览' })).not.toBeInTheDocument();
});

it('刷新和收起清空选择与预览，不持久化隐式授权', async () => {
  render(<AgentMemoryPreview status="ready" prompt="问题" />);
  await open();
  pickFirst();
  fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
  await screen.findByRole('region', { name: '已核对的记忆预览' });
  fireEvent.click(screen.getByRole('button', { name: '刷新可选记忆' }));
  await waitFor(() => expect(screen.getByLabelText(/偏好0/)).toBeEnabled());
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
  expect(screen.queryByRole('region', { name: '已核对的记忆预览' })).not.toBeInTheDocument();
  pickFirst();
  fireEvent.click(screen.getByRole('button', { name: '收起记忆选择' }));
  await open();
  expect(screen.getByText('已选择 0 / 5 条')).toBeVisible();
});

it('无原生连接时不读库或伪造记忆', () => {
  render(<AgentMemoryPreview status="unavailable" prompt="问题" />);
  fireEvent.click(screen.getByRole('button', { name: '选择本次记忆' }));
  expect(send).not.toHaveBeenCalled();
  expect(screen.getByText('请在手机 App 内选择记忆。')).toBeVisible();
});
