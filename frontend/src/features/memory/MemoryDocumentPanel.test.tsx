import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryDocumentPanel } from './MemoryDocumentPanel';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const id = '11111111-1111-4111-8111-111111111111';
const entry = {
  memory: {
    id,
    content: '合成偏好 <script>不执行</script>',
    source: 'user_explicit',
    version: 2,
    createdAtMillis: 1000,
    updatedAtMillis: 2000,
  },
  changeSource: 'agent',
  sourceTurnId: id,
  category: 'preference',
};
const page = (offset = 0, items = [entry], totalItems = items.length) => ({
  commandId: id,
  generatedAtMillis: 3000,
  offset,
  limit: 20,
  totalItems,
  items,
});
const accepted = (data: unknown) => ({ status: 'accepted', data });
beforeEach(() => {
  send.mockReset();
  send.mockResolvedValue(accepted(page()));
});

it('原生受限事实分类显示为事实，不冒充偏好', async () => {
  send.mockResolvedValueOnce(
    accepted(
      page(0, [
        { ...entry, category: 'fact', memory: { ...entry.memory, content: '我的专业是软件工程' } },
      ]),
    ),
  );
  render(<MemoryDocumentPanel ready onClose={() => {}} />);
  expect(await screen.findByText('事实 · 版本 2 · Agent 经授权写入')).toBeVisible();
  expect(screen.getByText('我的专业是软件工程')).toBeVisible();
});

it('仅显式展示原生文档，来源、版本与正文按文本呈现，不发在线或写入命令', async () => {
  render(<MemoryDocumentPanel ready onClose={() => {}} />);
  expect(await screen.findByText(entry.memory.content)).toBeVisible();
  expect(screen.getByText('偏好 · 版本 2 · Agent 经授权写入')).toBeVisible();
  expect(screen.getByText(/本页读取时间/)).toBeVisible();
  expect(document.querySelector('script')).toBeNull();
  expect(send.mock.calls).toEqual([['memory.document', { offset: 0, limit: 20 }]]);
});

it('分页覆盖后续记录，核对页码后替换原内容', async () => {
  const items = Array.from({ length: 20 }, (_, index) => ({
    ...entry,
    memory: {
      ...entry.memory,
      id: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
      content: `合成条目 ${index}`,
    },
  }));
  send
    .mockResolvedValueOnce(accepted(page(0, items, 21)))
    .mockResolvedValueOnce(
      accepted(page(20, [{ ...entry, memory: { ...entry.memory, content: '末页记忆' } }], 21)),
    );
  render(<MemoryDocumentPanel ready onClose={() => {}} />);
  await screen.findByText('合成条目 0');
  fireEvent.click(screen.getByRole('button', { name: '文档下一页' }));
  await screen.findByText('末页记忆');
  expect(screen.queryByText('合成条目 0')).not.toBeInTheDocument();
  expect(send.mock.calls[1]).toEqual(['memory.document', { offset: 20, limit: 20 }]);
});

it('未知结果不回显异常，重试不会自动写入或上传', async () => {
  send.mockRejectedValueOnce(new Error('秘密异常')).mockResolvedValueOnce(accepted(page(0, [], 0)));
  render(<MemoryDocumentPanel ready onClose={() => {}} />);
  await screen.findByRole('alert');
  expect(screen.queryByText(/秘密异常/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '重新读取文档' }));
  await screen.findByText('暂无有效记忆。');
  expect(send.mock.calls.every(([name]) => name === 'memory.document')).toBe(true);
});

it('断连后清除内容并忽略迟到回执，重连重新读取', async () => {
  let resolve!: (value: unknown) => void;
  send.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const view = render(<MemoryDocumentPanel ready onClose={() => {}} />);
  view.rerender(<MemoryDocumentPanel ready={false} onClose={() => {}} />);
  await act(async () => resolve(accepted(page())));
  expect(screen.queryByText(entry.memory.content)).not.toBeInTheDocument();
  view.rerender(<MemoryDocumentPanel ready onClose={() => {}} />);
  await screen.findByText(entry.memory.content);
  expect(send).toHaveBeenCalledTimes(2);
});

it('错误页码或坏来源回执不显示为已确认文档', async () => {
  send.mockResolvedValueOnce(accepted({ ...page(), offset: 20 }));
  render(<MemoryDocumentPanel ready onClose={() => {}} />);
  await screen.findByRole('alert');
  expect(screen.queryByText(entry.memory.content)).not.toBeInTheDocument();
  send.mockResolvedValueOnce(accepted(page(0, [{ ...entry, category: '伪造分类' }])));
  fireEvent.click(screen.getByRole('button', { name: '重新读取文档' }));
  await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  expect(screen.queryByText(entry.memory.content)).not.toBeInTheDocument();
});
