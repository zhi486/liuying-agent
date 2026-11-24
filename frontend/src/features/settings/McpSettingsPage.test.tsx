import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { McpSettingsPage } from './McpSettingsPage';
import fixture from '../../../../contracts/mcp-phone-v1/settings.json';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const accepted = (data: unknown) => ({ status: 'accepted', data });
it('目录损坏时列表仍可确认移除，不重新读取目录或自动补发', async () => {
  let resolveRemoval!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'mcp.remove'
      ? new Promise((resolve) => {
          resolveRemoval = resolve;
        })
      : Promise.resolve(
          name === 'mcp.detail'
            ? { status: 'rejected' }
            : accepted(name === 'mcp.status' ? fixture.status : fixture.list),
        ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  await screen.findByRole('alert');
  fireEvent.click(screen.getByText('无法管理？移除此服务'));
  const remove = screen.getByRole('button', { name: '确认移除列表中的服务' });
  expect(remove).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox', { name: /确认从列表移除此服务/ }));
  fireEvent.click(remove);
  fireEvent.click(remove);
  expect(send.mock.calls.filter(([name]) => name === 'mcp.remove')).toHaveLength(1);
  expect(send).toHaveBeenCalledWith('mcp.remove', {
    identityToken: fixture.status.identityToken,
    id: fixture.list.services[0].id,
    revision: fixture.list.services[0].revision,
    context: fixture.list.services[0].private.context,
    confirmed: true,
  });
  await act(async () =>
    resolveRemoval(
      accepted({
        commandId: fixture.status.commandId,
        identityToken: fixture.status.identityToken,
        removed: true,
      }),
    ),
  );
  expect(await screen.findByText(/服务已移除，这台手机的私密配置已撤销/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '管理此服务' })).not.toBeInTheDocument();
  expect(send.mock.calls.filter(([name]) => name === 'mcp.detail')).toHaveLength(1);
});
it('列表移除的确认在重新读取后失效，失败回执不冒充成功或自动补发', async () => {
  send.mockImplementation(async (name: string) =>
    name === 'mcp.remove'
      ? { status: 'rejected' }
      : accepted(name === 'mcp.status' ? fixture.status : fixture.list),
  );
  render(<McpSettingsPage />);
  await screen.findByRole('button', { name: '管理此服务' });
  fireEvent.click(screen.getByText('无法管理？移除此服务'));
  fireEvent.click(screen.getByRole('checkbox', { name: /确认从列表移除此服务/ }));
  fireEvent.click(screen.getByRole('button', { name: '重新读取配置' }));
  await screen.findByRole('button', { name: '管理此服务' });
  const disclosure = screen.getByText('无法管理？移除此服务').parentElement as HTMLDetailsElement;
  disclosure.open = true;
  expect(screen.getByRole('checkbox', { name: /确认从列表移除此服务/ })).not.toBeChecked();
  fireEvent.click(screen.getByRole('checkbox', { name: /确认从列表移除此服务/ }));
  fireEvent.click(screen.getByRole('button', { name: '确认移除列表中的服务' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('操作结果未确认');
  expect(screen.queryByText(/服务已移除，这台手机/)).not.toBeInTheDocument();
  expect(send.mock.calls.filter(([name]) => name === 'mcp.remove')).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '确认移除列表中的服务' })).not.toBeInTheDocument();
});
beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status' ? fixture.status : name === 'mcp.list' ? fixture.list : fixture.detail,
    ),
  );
});
it('进入页面仅查询配置，发现默认不授权，密钥不在网页填写', async () => {
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  expect(await screen.findByRole('button', { name: '发现工具目录' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '保存所选权限' })).toBeDisabled();
  expect(screen.getAllByRole('combobox').map((item) => (item as HTMLSelectElement).value)).toEqual([
    'off',
    'off',
  ]);
  expect(screen.queryByLabelText(/密钥$/)).not.toBeInTheDocument();
  expect(send.mock.calls.map(([name]) => name)).toEqual(['mcp.status', 'mcp.list', 'mcp.detail']);
  expect(screen.getByText(/正式后端升级与真机验收尚待完成/)).toBeInTheDocument();
});
it('原生私密配置只传绑定元数据，需明确勾选才能发现且不自动启用', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status' || name === 'mcp.discover'
        ? fixture.status
        : name === 'mcp.list'
          ? fixture.list
          : name === 'mcp.configure'
            ? {
                commandId: fixture.status.commandId,
                identityToken: fixture.status.identityToken,
                opened: true,
              }
            : fixture.detail,
    ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByRole('button', { name: '手机私密配置' }));
  await screen.findByText(/请在手机原生窗口填写/);
  expect(send).toHaveBeenCalledWith('mcp.configure', {
    identityToken: fixture.status.identityToken,
    id: fixture.detail.service.id,
    revision: fixture.detail.service.revision,
  });
  fireEvent.click(screen.getByRole('checkbox', { name: /同意本次由手机连接/ }));
  fireEvent.click(screen.getByRole('button', { name: '发现工具目录' }));
  await screen.findByText(/发现操作已结束/);
  expect(send.mock.calls.filter(([name]) => name === 'mcp.discover')).toHaveLength(1);
  expect(send.mock.calls.some(([name]) => name === 'mcp.approve')).toBe(false);
});
it('审核工具与权限后才保存，变更选择清除勾选，特殊工具名不继承原型值', async () => {
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  const choices = await screen.findAllByRole('combobox');
  fireEvent.change(choices[0], { target: { value: 'read' } });
  const approved = screen.getByRole('checkbox', { name: /我已审核/ });
  fireEvent.click(approved);
  fireEvent.change(choices[1], { target: { value: 'confirm' } });
  expect(approved).not.toBeChecked();
  fireEvent.click(approved);
  fireEvent.click(screen.getByRole('button', { name: '保存所选权限' }));
  await screen.findByText(/工具权限已保存；可到聊天选择本次使用的插件/);
  expect(send).toHaveBeenCalledWith('mcp.approve', {
    identityToken: fixture.status.identityToken,
    id: fixture.detail.service.id,
    revision: fixture.detail.service.revision,
    confirmed: true,
    selection: [
      { name: 'notes.search', mode: 'read' },
      { name: '__proto__', mode: 'confirm' },
    ],
  });
  expect(screen.getByRole('button', { name: '保存所选权限' })).toBeDisabled();
});
it('超时只读本机记录，恢复仅查询原标识，不自动重发或重复点击', async () => {
  let finish!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'mcp.discover'
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : Promise.resolve(
          accepted(
            name === 'mcp.status' || name === 'mcp.recover'
              ? fixture.status
              : name === 'mcp.list'
                ? fixture.list
                : fixture.detail,
          ),
        ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByRole('checkbox', { name: /同意本次由手机连接/ }));
  const discover = screen.getByRole('button', { name: '发现工具目录' });
  fireEvent.click(discover);
  fireEvent.click(discover);
  await act(async () =>
    finish({ status: 'rejected', error: { code: 'RESOURCE_UNAVAILABLE', message: 'synthetic' } }),
  );
  expect(await screen.findByRole('alert')).toHaveTextContent('操作结果未确认');
  await waitFor(() => expect(screen.getByRole('button', { name: '查询原发现记录' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询原发现记录' }));
  await waitFor(() =>
    expect(send).toHaveBeenCalledWith('mcp.recover', {
      identityToken: fixture.status.identityToken,
      requestId: fixture.status.operations[0].requestId,
    }),
  );
  expect(send.mock.calls.filter(([name]) => name === 'mcp.discover')).toHaveLength(1);
  expect(send.mock.calls.filter(([name]) => name === 'mcp.recover')).toHaveLength(1);
});
it('断开后的迟到回执不保留可操作目录', async () => {
  let finish!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'mcp.detail'
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : Promise.resolve(accepted(name === 'mcp.status' ? fixture.status : fixture.list)),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  act(() => useBridgeStore.setState({ status: 'connecting' }));
  await act(async () => finish(accepted(fixture.detail)));
  expect(screen.queryByRole('button', { name: '手机私密配置' })).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('请在手机连接就绪后管理插件');
});
it('后端未支持时不把空列表当成功，但仍可恢复本机发现记录', async () => {
  send.mockImplementation(async (name: string) =>
    name === 'mcp.list' ? { status: 'rejected' } : accepted(fixture.status),
  );
  render(<McpSettingsPage />);
  expect(await screen.findByRole('alert')).toHaveTextContent('云端可能尚未支持');
  expect(screen.getByRole('button', { name: '仅登记服务' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '查询原发现记录' })).toBeEnabled();
  expect(screen.queryByText('还没有插件服务，登记后再选择工具权限。')).not.toBeInTheDocument();
});
it('身份不一致的目录回执拒绝展示，不能用旧令牌继续审批', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status'
        ? fixture.status
        : name === 'mcp.list'
          ? fixture.list
          : { ...fixture.detail, identityToken: 'd'.repeat(64) },
    ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('操作结果未确认');
  expect(screen.queryByRole('button', { name: '保存所选权限' })).not.toBeInTheDocument();
});
it('非公开或含查询参数地址在网页本地拒绝，不发登记请求', async () => {
  render(<McpSettingsPage />);
  await screen.findByRole('button', { name: '管理此服务' });
  fireEvent.change(screen.getByLabelText('服务地址（不能包含密钥）'), {
    target: { value: 'https://127.0.0.1/mcp?key=synthetic' },
  });
  fireEvent.click(screen.getByRole('button', { name: '仅登记服务' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('请填写规范的公开 HTTPS 地址');
  expect(send.mock.calls.some(([name]) => name === 'mcp.register')).toBe(false);
});
it('修改地址需确认，地址再次编辑清除确认，新版本清空目录和权限选择', async () => {
  const address = 'https://changed.example.com/mcp';
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status'
        ? fixture.status
        : name === 'mcp.list'
          ? fixture.list
          : name === 'mcp.endpoint'
            ? {
                ...fixture.detail,
                service: {
                  ...fixture.detail.service,
                  endpoint: address,
                  revision: '77777777-7777-4777-8777-777777777777',
                  enabled: false,
                  catalog: null,
                  grants: [],
                },
                private: {
                  ...fixture.detail.private,
                  configured: false,
                  hasCredential: false,
                  context: 'c'.repeat(64),
                  endpointMatches: false,
                },
              }
            : fixture.detail,
    ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByText('修改地址或移除服务'));
  const input = screen.getByLabelText('新的服务地址');
  const confirm = screen.getByRole('checkbox', {
    name: '确认修改地址，并撤销旧连接配置和工具权限。',
  });
  const save = screen.getByRole('button', { name: '确认修改地址' });
  expect(save).toBeDisabled();
  fireEvent.change(input, { target: { value: 'https://draft.example.com/mcp' } });
  fireEvent.click(confirm);
  fireEvent.change(input, { target: { value: address } });
  expect(confirm).not.toBeChecked();
  expect(save).toBeDisabled();
  fireEvent.click(confirm);
  fireEvent.click(save);
  expect(await screen.findByText(/服务地址已修改，旧目录/)).toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('mcp.endpoint', {
    identityToken: fixture.status.identityToken,
    id: fixture.detail.service.id,
    revision: fixture.detail.service.revision,
    context: fixture.detail.private.context,
    confirmed: true,
    endpoint: address,
  });
  expect(screen.getByText('尚无已确认目录。')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '发现工具目录' })).toBeDisabled();
  expect(screen.queryByText('手机已存密钥')).not.toBeInTheDocument();
  fireEvent.click(screen.getByText('修改地址或移除服务'));
  expect(screen.getByRole('button', { name: '确认移除服务' })).toBeDisabled();
});
it('确认移除仅发送当前审核绑定，成功后清除服务但保留原发现查询', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status'
        ? fixture.status
        : name === 'mcp.list'
          ? fixture.list
          : name === 'mcp.remove'
            ? {
                commandId: fixture.status.commandId,
                identityToken: fixture.status.identityToken,
                removed: true,
              }
            : fixture.detail,
    ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByText('修改地址或移除服务'));
  const remove = screen.getByRole('button', { name: '确认移除服务' });
  expect(remove).toBeDisabled();
  fireEvent.click(
    screen.getByRole('checkbox', { name: '确认移除此服务，并撤销这台手机的私密配置。' }),
  );
  fireEvent.click(remove);
  fireEvent.click(remove);
  expect(await screen.findByText(/服务已移除，这台手机的私密配置已撤销/)).toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('mcp.remove', {
    identityToken: fixture.status.identityToken,
    id: fixture.detail.service.id,
    revision: fixture.detail.service.revision,
    context: fixture.detail.private.context,
    confirmed: true,
  });
  expect(send.mock.calls.filter(([name]) => name === 'mcp.remove')).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '管理此服务' })).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: '当前服务管理' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '查询原发现记录' })).toBeEnabled();
});
it('移除拒绝或错误成功回执不冒充成功，只读本机状态且不补发', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(
      name === 'mcp.status'
        ? fixture.status
        : name === 'mcp.list'
          ? fixture.list
          : name === 'mcp.remove'
            ? {
                commandId: fixture.status.commandId,
                identityToken: fixture.status.identityToken,
                removed: false,
              }
            : fixture.detail,
    ),
  );
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByText('修改地址或移除服务'));
  fireEvent.click(
    screen.getByRole('checkbox', { name: '确认移除此服务，并撤销这台手机的私密配置。' }),
  );
  fireEvent.click(screen.getByRole('button', { name: '确认移除服务' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('手机私密配置可能已撤销');
  expect(screen.queryByText(/服务已移除，这台手机/)).not.toBeInTheDocument();
  await waitFor(() => expect(send.mock.calls.at(-1)?.[0]).toBe('mcp.status'));
  expect(send.mock.calls.filter(([name]) => name === 'mcp.remove')).toHaveLength(1);
  expect(send.mock.calls.filter(([name]) => name === 'mcp.list')).toHaveLength(1);
});
it('修改服务使用同样的公开地址校验，重新读取后确认不会保留', async () => {
  render(<McpSettingsPage />);
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByText('修改地址或移除服务'));
  fireEvent.change(screen.getByLabelText('新的服务地址'), {
    target: { value: 'https://tools.example.com/mcp?key=synthetic' },
  });
  fireEvent.click(
    screen.getByRole('checkbox', { name: '确认修改地址，并撤销旧连接配置和工具权限。' }),
  );
  fireEvent.click(
    screen.getByRole('checkbox', { name: '确认移除此服务，并撤销这台手机的私密配置。' }),
  );
  fireEvent.click(screen.getByRole('button', { name: '确认修改地址' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '请填写与当前不同的规范公开 HTTPS 地址',
  );
  expect(send.mock.calls.some(([name]) => name === 'mcp.endpoint')).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: '重新读取配置' }));
  fireEvent.click(await screen.findByRole('button', { name: '管理此服务' }));
  fireEvent.click(await screen.findByText('修改地址或移除服务'));
  expect(screen.getByRole('button', { name: '确认移除服务' })).toBeDisabled();
});
