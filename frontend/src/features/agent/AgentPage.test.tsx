import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';

import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentPage } from './AgentPage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: (name: string, body: unknown) =>
    name === 'cloud.list'
      ? Promise.resolve({
          status: 'accepted',
          data: {
            commandId: '33333333-3333-4333-8333-333333333333',
            configured: false,
            configuring: false,
            origin: null,
            items: [],
          },
        })
      : send(name, body),
}));

const id = '22222222-2222-4222-8222-222222222222';
const commandId = '33333333-3333-4333-8333-333333333333';
const summary = {
  id,
  title: '今晚轻松跑',
  status: 'blocked',
  reason: 'cloud_not_connected',
  createdAtMillis: 1000,
  updatedAtMillis: 1000,
};
const detail = {
  commandId,
  task: summary,
  messages: [{ sequence: 0, role: 'user', text: '今晚轻松跑', createdAtMillis: 1000 }],
  actions: [{ sequence: 0, kind: 'saved_locally', createdAtMillis: 1000 }],
};
const cancelled = {
  ...detail,
  task: { ...summary, status: 'cancelled', reason: null, updatedAtMillis: 2000 },
  actions: [...detail.actions, { sequence: 1, kind: 'cancelled', createdAtMillis: 2000 }],
};
const accepted = (data: unknown) => ({ status: 'accepted', data });
const page = (items: unknown[] = []) => ({
  commandId,
  offset: 0,
  limit: 20,
  totalItems: items.length,
  items,
});
const mount = () =>
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
const openAccount = () => fireEvent.click(screen.getByRole('button', { name: '打开侧边栏' }));
const openReference = () => {
  fireEvent.click(screen.getByLabelText('更多输入选项'));
  fireEvent.click(screen.getByText('本次参考'));
};
const waitForTasks = () =>
  waitFor(() => expect(send.mock.calls.some(([name]) => name === 'agent.list')).toBe(true));

beforeEach(() => {
  send.mockReset();
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  send.mockResolvedValue(accepted(page()));
});

it('选中并核对记忆后仅保存仍只保存问题，不携带记忆或上传授权', async () => {
  const memory = {
    id,
    version: 1,
    content: '偏好晚跑',
    source: 'user_explicit',
    createdAtMillis: 1000,
    updatedAtMillis: 1000,
  };
  let saved = false;
  send.mockImplementation(async (name: string) => {
    if (name === 'memory.list') return accepted(page([memory]));
    if (name === 'memory.preview')
      return accepted({ commandId, scope: 'local_preview', items: [memory] });
    if (name === 'agent.submit') {
      saved = true;
      return accepted(detail);
    }
    return accepted(page(saved ? [summary] : []));
  });
  mount();
  await waitForTasks();
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚轻松跑' } });
  openReference();
  fireEvent.click(screen.getByRole('button', { name: '选择本次记忆' }));
  fireEvent.click(await screen.findByLabelText(/偏好晚跑/));
  fireEvent.click(screen.getByRole('button', { name: '核对本次记忆' }));
  await screen.findByRole('region', { name: '已核对的记忆预览' });
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await screen.findByText(/已保存到本机，可在账户详情查看/);
  const submitted = send.mock.calls.find(([name]) => name === 'agent.submit')![1];
  expect(submitted).toEqual({ clientRequestId: expect.any(String), prompt: '今晚轻松跑' });
  expect(screen.getByRole('button', { name: '在线执行' })).toBeDisabled();
  expect(
    send.mock.calls.every(([name]) =>
      ['agent.list', 'agent.submit', 'memory.list', 'memory.preview'].includes(name),
    ),
  ).toBe(true);
});

it('两种入口明确分开，在线未配置时连点不发送也不降级保存', async () => {
  mount();
  await waitForTasks();
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚轻松跑' } });
  expect(screen.getByRole('button', { name: '仅保存' })).toBeEnabled();
  const online = screen.getByRole('button', { name: '在线执行' });
  expect(online).toBeDisabled();
  expect(online).toHaveAccessibleDescription('需单独配置在线 Agent');
  expect(online).toHaveAccessibleDescription('需单独配置在线 Agent');
  fireEvent.click(online);
  fireEvent.click(online);
  expect(send.mock.calls.map(([name]) => name)).toEqual(['agent.list']);
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('今晚轻松跑');
  expect(screen.queryByText(/正在上传|执行成功|发送成功/)).not.toBeInTheDocument();
});

it.each(['unavailable', 'ready', 'error'] as const)(
  '%s 时查看上传说明不提交，草稿保留且本机入口状态正确',
  async (status) => {
    useBridgeStore.setState({ status, snapshot: null });
    mount();
    if (status === 'ready') await waitForTasks();
    const input = screen.getByLabelText('给流萤的消息');
    fireEvent.change(input, { target: { value: '先记下来' } });
    const before = send.mock.calls.length;
    openReference();
    const disclosure = screen.getByText('在线执行会上传什么？');
    fireEvent.click(disclosure);
    expect(disclosure.closest('details')).toHaveAttribute('open');
    expect(screen.getByText(/每次执行都需你确认/)).toBeVisible();
    expect(screen.getByText(/输入中包含的个人信息也会随文字发送/)).toBeVisible();
    fireEvent.click(disclosure);
    expect(disclosure.closest('details')).not.toHaveAttribute('open');
    expect(input).toHaveValue('先记下来');
    expect(send).toHaveBeenCalledTimes(before);
    expect(screen.getByRole('button', { name: '在线执行' })).toBeDisabled();
    const save = screen.getByRole('button', { name: '仅保存' });
    if (status === 'ready') expect(save).toBeEnabled();
    else expect(save).toBeDisabled();
  },
);

it('未接原生时不读写任务，不展示假任务', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  mount();
  expect(screen.getByRole('button', { name: '仅保存' })).toBeDisabled();
  expect(send).not.toHaveBeenCalled();
  openAccount();
  expect(screen.getByText(/请在手机 App 内保存和查看任务/)).toBeVisible();
});

it('本机任务只在账户详情展示，不挤走首页输入入口', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  mount();
  expect(screen.getByLabelText('给流萤的消息')).toBeVisible();
  expect(screen.queryByRole('heading', { name: '本机任务' })).not.toBeInTheDocument();
  openAccount();
  expect(screen.getByRole('heading', { name: '本机任务' })).toBeVisible();
});

it('首页只保留对话操作，低频管理入口集中在账户详情', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  mount();
  expect(screen.getByRole('button', { name: '语音输入' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '配置在线 Agent' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '管理长期记忆' })).not.toBeInTheDocument();
  openAccount();
  expect(screen.getByRole('link', { name: '管理长期记忆' })).toHaveAttribute('href', '/memories');
  expect(screen.getByRole('link', { name: '跑步记录' })).toHaveAttribute('href', '/history');
  expect(screen.getByRole('link', { name: '运动与语音设置' })).toHaveAttribute('href', '/settings');
  expect(screen.getByRole('button', { name: '配置在线 Agent' })).toBeInTheDocument();
});

it('图标顶栏打开抽屉，Esc 关闭后焦点回到入口', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  mount();
  const trigger = screen.getByRole('button', { name: '打开侧边栏' });
  expect(trigger.textContent).toBe('');
  fireEvent.click(trigger);
  expect(screen.getByRole('dialog', { name: '流萤' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '关闭侧边栏' })).toHaveFocus();
  fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: '流萤' })).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});

it('麦克风和发送为图标，低频保存藏在加号菜单', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  mount();
  expect(screen.getByRole('button', { name: '语音输入' }).textContent).toBe('');
  expect(screen.getByRole('button', { name: '在线执行' })).toHaveTextContent('在线执行');
  const menu = screen.getByLabelText('更多输入选项');
  expect(menu.closest('details')).not.toHaveAttribute('open');
  fireEvent.click(menu);
  expect(menu.closest('details')).toHaveAttribute('open');
  expect(screen.getByRole('button', { name: '仅保存' })).toBeInTheDocument();
});

it('输入聚焦或软键盘改变视口时滚动完整输入区，失焦与卸载不再滚动', () => {
  useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  const scroll = vi.fn();
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scroll,
  });
  try {
    const mounted = mount();
    const input = screen.getByLabelText('给流萤的消息');
    act(() => input.focus());
    expect(scroll).toHaveBeenCalledWith({ block: 'center' });
    scroll.mockClear();
    fireEvent(window, new Event('resize'));
    expect(scroll).toHaveBeenCalledTimes(1);
    act(() => input.blur());
    fireEvent(window, new Event('resize'));
    expect(scroll).toHaveBeenCalledTimes(1);
    mounted.unmount();
    fireEvent(window, new Event('resize'));
    expect(scroll).toHaveBeenCalledTimes(1);
  } finally {
    if (original) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original);
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
  }
});

it('真实空态与保存后本机记录，不生成助手回复', async () => {
  mount();
  await waitForTasks();
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚轻松跑' } });
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.submit' ? detail : page([summary])),
  );
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await screen.findByText(/已保存到本机，可在账户详情查看/);
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('');
  openAccount();
  const region = await screen.findByRole('region', { name: '任务详情' });
  expect(within(region).getByText('今晚轻松跑')).toBeVisible();
  expect(within(region).getByText(/未上传，也未执行/)).toBeVisible();
  expect(screen.queryByText(/发送成功|流萤回复/)).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('agent.submit', {
    clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    prompt: '今晚轻松跑',
  });
});

it('保存结果未知保留草稿，连点只保存一次，手动重试复用幂等键', async () => {
  let reject!: (reason: unknown) => void;
  mount();
  await waitForTasks();
  send.mockImplementation((name: string) =>
    name === 'agent.submit'
      ? new Promise((_resolve, fail) => {
          reject = fail;
        })
      : Promise.resolve(accepted(page())),
  );
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚轻松跑' } });
  const save = screen.getByRole('button', { name: '仅保存' });
  fireEvent.click(save);
  fireEvent.click(save);
  expect(send.mock.calls.filter(([name]) => name === 'agent.submit')).toHaveLength(1);
  await act(async () => reject(new Error('结果未知')));
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('今晚轻松跑');
  send.mockResolvedValue(accepted(detail));
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await screen.findByText(/已保存到本机，可在账户详情查看/);
  const attempts = send.mock.calls.filter(([name]) => name === 'agent.submit');
  expect(attempts[0][1].clientRequestId).toBe(attempts[1][1].clientRequestId);
});

it('修改草稿后不误用旧幂等键，空白和超长输入不保存', async () => {
  mount();
  await waitForTasks();
  send.mockRejectedValue(new Error('保存失败'));
  const field = screen.getByLabelText('给流萤的消息');
  fireEvent.change(field, { target: { value: '  ' } });
  expect(screen.getByRole('button', { name: '仅保存' })).toBeDisabled();
  fireEvent.change(field, { target: { value: '字'.repeat(2001) } });
  expect(screen.getByRole('button', { name: '仅保存' })).toBeDisabled();
  fireEvent.change(field, { target: { value: '第一次' } });
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await screen.findByRole('alert');
  fireEvent.change(field, { target: { value: '第二次' } });
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await waitFor(() =>
    expect(send.mock.calls.filter(([name]) => name === 'agent.submit')).toHaveLength(2),
  );
  const attempts = send.mock.calls.filter(([name]) => name === 'agent.submit');
  expect(attempts[0][1].clientRequestId).not.toBe(attempts[1][1].clientRequestId);
});

it('重新挂载只读取本机记录，不自动重发任务', async () => {
  send.mockResolvedValue(accepted(page([summary])));
  const first = mount();
  openAccount();
  await screen.findByRole('button', { name: '查看任务：今晚轻松跑' });
  first.unmount();
  mount();
  openAccount();
  await screen.findByRole('button', { name: '查看任务：今晚轻松跑' });
  expect(send.mock.calls.map(([name]) => name)).toEqual(['agent.list', 'agent.list']);
});

it('取消成功以原生状态为准，失败不乐观改成已取消', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.list' ? page([summary]) : detail),
  );
  mount();
  openAccount();
  fireEvent.click(await screen.findByRole('button', { name: '查看任务：今晚轻松跑' }));
  const region = await screen.findByRole('region', { name: '任务详情' });
  send.mockResolvedValueOnce({
    status: 'rejected',
    error: { code: 'INTERNAL_ERROR', message: '取消失败' },
  });
  fireEvent.click(within(region).getByRole('button', { name: '取消任务' }));
  await screen.findByRole('alert');
  expect(within(region).getByRole('button', { name: '取消任务' })).toBeEnabled();
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.cancel' ? cancelled : page([cancelled.task])),
  );
  fireEvent.click(within(region).getByRole('button', { name: '取消任务' }));
  await waitFor(() =>
    expect(within(region).queryByRole('button', { name: '取消任务' })).not.toBeInTheDocument(),
  );
  expect(within(region).getByText('已取消')).toBeVisible();
});

it('关闭详情后迟到结果不重新打开，ID 不匹配拒绝展示', async () => {
  let resolve!: (value: unknown) => void;
  send.mockImplementation((name: string) =>
    name === 'agent.detail'
      ? new Promise((done) => {
          resolve = done;
        })
      : Promise.resolve(accepted(page([summary]))),
  );
  mount();
  openAccount();
  fireEvent.click(await screen.findByRole('button', { name: '查看任务：今晚轻松跑' }));
  fireEvent.click(screen.getByRole('button', { name: '关闭详情' }));
  await act(async () => resolve(accepted(detail)));
  expect(screen.queryByRole('region', { name: '任务详情' })).not.toBeInTheDocument();
  send.mockResolvedValue(accepted({ ...detail, task: { ...summary, id: commandId } }));
  fireEvent.click(screen.getByRole('button', { name: '查看任务：今晚轻松跑' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(/任务详情/);
});

it('Bridge 失效后隐藏旧详情，不能继续取消', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.list' ? page([summary]) : detail),
  );
  mount();
  openAccount();
  fireEvent.click(await screen.findByRole('button', { name: '查看任务：今晚轻松跑' }));
  await screen.findByRole('button', { name: '取消任务' });
  act(() => useBridgeStore.setState({ status: 'error', snapshot: null }));
  expect(screen.queryByRole('region', { name: '任务详情' })).not.toBeInTheDocument();
});

it('保存失败会关闭被中断的详情，旧详情晚到不留下永久加载态', async () => {
  let resolveDetail!: (value: unknown) => void;
  send.mockImplementation((name: string) => {
    if (name === 'agent.detail')
      return new Promise((resolve) => {
        resolveDetail = resolve;
      });
    if (name === 'agent.submit') return Promise.reject(new Error('保存失败'));
    return Promise.resolve(accepted(page([summary])));
  });
  mount();
  openAccount();
  fireEvent.click(await screen.findByRole('button', { name: '查看任务：今晚轻松跑' }));
  expect(screen.getByText('正在读取任务详情…')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '关闭侧边栏' }));
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '新任务' } });
  fireEvent.click(screen.getByRole('button', { name: '仅保存' }));
  await screen.findByRole('alert');
  await act(async () => resolveDetail(accepted(detail)));
  openAccount();
  expect(screen.queryByRole('region', { name: '任务详情' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '关闭侧边栏' }));
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('新任务');
  openAccount();
  send.mockResolvedValue(accepted(detail));
  fireEvent.click(screen.getByRole('button', { name: '查看任务：今晚轻松跑' }));
  expect(await screen.findByRole('button', { name: '取消任务' })).toBeEnabled();
});

it('取消后旧列表回包不能覆盖已取消状态', async () => {
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.list' ? page([summary]) : detail),
  );
  mount();
  openAccount();
  fireEvent.click(await screen.findByRole('button', { name: '查看任务：今晚轻松跑' }));
  await screen.findByRole('button', { name: '取消任务' });
  let oldList!: (value: unknown) => void;
  send.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        oldList = resolve;
      }),
  );
  fireEvent.click(screen.getByRole('button', { name: '刷新任务' }));
  send.mockImplementation(async (name: string) =>
    accepted(name === 'agent.cancel' ? cancelled : page([cancelled.task])),
  );
  fireEvent.click(screen.getByRole('button', { name: '取消任务' }));
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '取消任务' })).not.toBeInTheDocument(),
  );
  await act(async () => oldList(accepted(page([summary]))));
  expect(screen.getByRole('button', { name: '查看任务：今晚轻松跑' })).toHaveTextContent('已取消');
});

it('分页读取后续任务，重复点加载只产生一个分页请求', async () => {
  const first = Array.from({ length: 20 }, (_, index) => ({
    ...summary,
    id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    title: `任务${index}`,
  }));
  send.mockResolvedValue(accepted({ ...page(first), totalItems: 21 }));
  mount();
  openAccount();
  const more = await screen.findByRole('button', { name: '加载更多任务' });
  let resolve!: (value: unknown) => void;
  send.mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  fireEvent.click(more);
  fireEvent.click(more);
  expect(send.mock.calls.filter(([, body]) => body.offset === 20)).toHaveLength(1);
  await act(async () => resolve(accepted({ ...page([summary]), offset: 20, totalItems: 21 })));
  expect(screen.getByRole('button', { name: '查看任务：今晚轻松跑' })).toBeVisible();
  expect(screen.queryByRole('button', { name: '加载更多任务' })).not.toBeInTheDocument();
});
