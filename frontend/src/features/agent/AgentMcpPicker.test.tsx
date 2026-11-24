import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import fixture from '../../../../contracts/mcp-phone-v1/settings.json';
import { AgentMcpPicker } from './AgentMcpPicker';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const accepted = (data: unknown) => ({ status: 'accepted', data });
const row = { ...fixture.list.services[0], enabled: true };
function Harness({ scopeKey = 'one' }: { scopeKey?: string }) {
  const [selected, setSelected] = useState<string[]>([]);
  return (
    <MemoryRouter>
      <AgentMcpPicker
        selected={selected}
        onChange={setSelected}
        disabled={false}
        scopeKey={scopeKey}
      />
    </MemoryRouter>
  );
}
beforeEach(() => {
  send.mockReset();
  send.mockImplementation(async (name: string) =>
    accepted(name === 'mcp.status' ? fixture.status : { ...fixture.list, services: [row] }),
  );
});
it('默认不查询不选择，显式读取目录只发送身份绑定且禁用未配置服务', async () => {
  render(<Harness />);
  expect(send).not.toHaveBeenCalled();
  expect(screen.getByRole('link', { name: '管理 MCP 插件' })).toHaveAttribute(
    'href',
    '/settings/plugins',
  );
  fireEvent.click(screen.getByRole('button', { name: '读取可用插件' }));
  const checkbox = await screen.findByRole('checkbox');
  expect(checkbox).not.toBeChecked();
  fireEvent.click(checkbox);
  expect(screen.getByText('本次可用插件（1/8）')).toBeInTheDocument();
  expect(send.mock.calls).toEqual([
    ['mcp.status', {}],
    ['mcp.list', { identityToken: fixture.status.identityToken, after: null }],
  ]);
  send.mockResolvedValueOnce(accepted(fixture.status)).mockResolvedValueOnce(
    accepted({
      ...fixture.list,
      services: [{ ...row, private: { ...row.private, endpointMatches: false } }],
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '重新读取插件目录' }));
  await screen.findByText('需先在手机配置此服务');
  expect(screen.getByRole('checkbox')).toBeDisabled();
  expect(screen.getByRole('checkbox')).not.toBeChecked();
});
it('身份改变丢弃迟到目录，不执行工具或自动重试', async () => {
  let finish!: (result: unknown) => void;
  send.mockImplementation(async (name: string) =>
    name === 'mcp.status'
      ? accepted(fixture.status)
      : new Promise((resolve) => {
          finish = resolve;
        }),
  );
  const view = render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: '读取可用插件' }));
  await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  view.rerender(<Harness scopeKey="two" />);
  await act(async () => finish(accepted({ ...fixture.list, services: [row] })));
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledTimes(2);
});
it('身份不匹配或目录重复被拒绝且不会保留选择', async () => {
  send
    .mockResolvedValueOnce(accepted(fixture.status))
    .mockResolvedValueOnce(accepted({ ...fixture.list, identityToken: 'f'.repeat(64) }));
  render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: '读取可用插件' }));
  await screen.findByRole('alert');
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  send
    .mockResolvedValueOnce(accepted(fixture.status))
    .mockResolvedValueOnce(accepted({ ...fixture.list, services: [row, row] }));
  fireEvent.click(screen.getByRole('button', { name: '读取可用插件' }));
  await waitFor(() => expect(send).toHaveBeenCalledTimes(4));
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
});
