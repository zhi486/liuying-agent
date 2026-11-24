import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryDocumentSave } from './MemoryDocumentSave';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const commandId = '11111111-1111-4111-8111-111111111111';
const result = (exportId: string, phase: string) => ({
  status: 'accepted',
  data: {
    commandId,
    exportId,
    phase,
    totalItems: phase === 'saved' ? 121 : null,
    generatedAtMillis: phase === 'saved' ? 3000 : null,
  },
});
beforeEach(() => {
  send.mockReset();
});

it('仅用户点击发起选择器并且查询同一次完整保存，不传页面正文或路径', async () => {
  send.mockImplementation(async (name, body) =>
    result(body.exportId, name === 'memory.exportBegin' ? 'choosing' : 'saved'),
  );
  render(<MemoryDocumentSave ready />);
  expect(send).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  await screen.findByText(/请在系统文件窗口/);
  expect(screen.getByRole('button', { name: '保存完整 Markdown' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  expect(send).toHaveBeenCalledTimes(1);
  await screen.findByText(/已保存 121 条/, {}, { timeout: 2500 });
  expect(send.mock.calls).toEqual([
    ['memory.exportBegin', { exportId: expect.any(String), confirmed: true }],
    ['memory.exportStatus', { exportId: send.mock.calls[0][1].exportId }],
  ]);
});

it.each(['cancelled', 'failed', 'interrupted'])(
  '终态 %s 不报告成功也不自动重新导出',
  async (phase) => {
    send.mockImplementation(async (_, body) => result(body.exportId, phase));
    const view = render(<MemoryDocumentSave ready />);
    fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '保存完整 Markdown' })).toBeEnabled(),
    );
    expect(screen.queryByText(/已保存 \d/)).not.toBeInTheDocument();
    expect(send).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(send).toHaveBeenCalledTimes(1);
  },
);

it('发起回执丢失后只显式查询同一操作，不自动重新打开选择器', async () => {
  send
    .mockRejectedValueOnce(new Error('私密异常'))
    .mockImplementation(async (_, body) => result(body.exportId, 'saved'));
  render(<MemoryDocumentSave ready />);
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  await screen.findByRole('alert');
  expect(screen.queryByText('私密异常')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '查询本次保存结果' }));
  await screen.findByText(/已保存 121 条/);
  expect(send.mock.calls[1]).toEqual([
    'memory.exportStatus',
    { exportId: send.mock.calls[0][1].exportId },
  ]);
});

it('离开或断连取消未结束保存并忽略迟到成功，重连可操作', async () => {
  let resolve!: (value: unknown) => void;
  send
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    )
    .mockResolvedValue({ status: 'accepted' });
  const view = render(<MemoryDocumentSave ready />);
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  const exportId = send.mock.calls[0][1].exportId;
  view.rerender(<MemoryDocumentSave ready={false} />);
  expect(send.mock.calls[1]).toEqual(['memory.exportCancel', { exportId }]);
  await act(async () => resolve(result(exportId, 'saved')));
  expect(screen.queryByText(/已保存 121 条/)).not.toBeInTheDocument();
  view.rerender(<MemoryDocumentSave ready />);
  expect(screen.getByRole('button', { name: '保存完整 Markdown' })).toBeEnabled();
});

it('错误操作或无条目数的成功回执不显示为已保存', async () => {
  send.mockResolvedValue(result(commandId, 'saved'));
  render(<MemoryDocumentSave ready />);
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  await screen.findByRole('alert');
  expect(screen.queryByText(/已保存 121 条/)).not.toBeInTheDocument();
});

it('收起未完成的文档时取消同一次保存，不再轮询或换标识重做', async () => {
  send.mockImplementation(async (_, body) => result(body.exportId, 'choosing'));
  const view = render(<MemoryDocumentSave ready />);
  fireEvent.click(screen.getByRole('button', { name: '保存完整 Markdown' }));
  await screen.findByText(/请在系统文件窗口/);
  const exportId = send.mock.calls[0][1].exportId;
  view.unmount();
  expect(send.mock.calls).toEqual([
    ['memory.exportBegin', { exportId, confirmed: true }],
    ['memory.exportCancel', { exportId }],
  ]);
});
