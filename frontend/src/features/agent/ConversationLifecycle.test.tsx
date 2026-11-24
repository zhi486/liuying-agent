import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import {
  cloudCommandSchemas,
  cloudListingSchema,
  type CloudListing,
} from '../../bridge/cloudAgent';
import { AgentPage } from './AgentPage';
import { AgentUsagePage } from '../settings/AgentUsagePage';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const id = '11111111-1111-4111-8111-111111111111';
const archivedId = '22222222-2222-4222-8222-222222222222';
const requestId = '33333333-3333-4333-8333-333333333333';
const row: CloudListing['items'][number] = {
  requestId,
  prompt: '合成问题',
  answer: '合成回答',
  status: 'completed',
  resultState: 'acknowledged',
  attempted: true,
  busy: false,
  pendingControl: null,
  confirmedControl: null,
  reason: null,
  error: null,
  memoryRefs: [],
};
let listing: CloudListing;
const accepted = (data: unknown) => ({ status: 'accepted', data });
const mount = () =>
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );
const openSidebar = () => fireEvent.click(screen.getByRole('button', { name: '打开侧边栏' }));
const openManagement = () => {
  openSidebar();
  fireEvent.click(screen.getByRole('button', { name: '设置与管理' }));
};
const mutations = () =>
  send.mock.calls.filter(([name]) => !['cloud.list', 'agent.list'].includes(name));

beforeEach(() => {
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  listing = {
    commandId: id,
    configured: true,
    configuring: false,
    origin: 'https://agent.example.com',
    conversationId: id,
    conversationArchived: false,
    legacyAvailable: false,
    legacyArchived: false,
    conversations: [
      { id, title: '当前合成对话', updatedAtMillis: 1000 },
      { id: archivedId, title: '已归档合成对话', updatedAtMillis: 1000, archived: true },
    ],
    items: [row],
    cleanupPending: 0,
    cleanupBusy: false,
  };
  send.mockReset();
  send.mockImplementation(
    async (name: string, body: { conversationId?: string | null; title?: string }) => {
      if (name === 'agent.list')
        return accepted({ commandId: id, offset: 0, limit: 20, totalItems: 0, items: [] });
      if (name === 'cloud.list') return accepted(listing);
      if (name === 'cloud.renameConversation') {
        listing = {
          ...listing,
          conversations: listing.conversations?.map((item) =>
            item.id === body.conversationId ? { ...item, title: body.title ?? item.title } : item,
          ),
        };
      } else if (name === 'cloud.selectConversation') {
        listing = {
          ...listing,
          conversationId: body.conversationId,
          conversationArchived:
            body.conversationId === archivedId ||
            (body.conversationId === null && listing.legacyArchived),
        };
      } else if (name === 'cloud.archiveConversation' || name === 'cloud.restoreConversation') {
        const archived = name === 'cloud.archiveConversation';
        listing = {
          ...listing,
          conversations: listing.conversations?.map((item) =>
            item.id === body.conversationId ? { ...item, archived } : item,
          ),
          ...(body.conversationId === null ? { legacyArchived: archived } : {}),
          ...(listing.conversationId === body.conversationId
            ? archived
              ? { conversationId: null, conversationArchived: false, items: [] }
              : { conversationArchived: false }
            : {}),
        };
      } else if (name === 'cloud.deleteConversation') {
        listing = {
          ...listing,
          conversations: listing.conversations?.filter((item) => item.id !== body.conversationId),
          cleanupPending: 1,
          ...(body.conversationId === null ? { legacyAvailable: false } : {}),
          ...(listing.conversationId === body.conversationId
            ? { conversationId: null, conversationArchived: false, items: [] }
            : {}),
        };
      } else if (name === 'cloud.retryConversationCleanup')
        listing = { ...listing, cleanupPending: 0 };
      else throw new Error(`禁止命令：${name}`);
      return accepted(listing);
    },
  );
});

it('最近会话及标题搜索不显示归档，逐条入口可发现，取消后返回原操作焦点', async () => {
  mount();
  await screen.findByText(row.answer!);
  openSidebar();
  expect(
    screen.queryByRole('button', { name: '打开会话：已归档合成对话' }),
  ).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('搜索会话标题'), { target: { value: '已归档' } });
  expect(screen.getByText('没有匹配的会话标题')).toBeVisible();
  fireEvent.change(screen.getByLabelText('搜索会话标题'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: '管理对话：当前合成对话' }));
  fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
  expect(screen.getByRole('region', { name: '确认删除对话' })).toHaveTextContent('当前合成对话');
  expect(screen.getByText(/删除后无法恢复/)).toBeVisible();
  expect(screen.getByText(/长期记忆、跑步记录及必要用量记录不会删除/)).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '取消删除' }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '管理对话：当前合成对话' })).toHaveFocus(),
  );
  expect(mutations()).toEqual([]);
});

it('会话管理 B 保留两组玻璃操作、行内重命名和删除确认', async () => {
  mount();
  await screen.findByText(row.answer!);
  openSidebar();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：当前合成对话' }));

  expect(screen.getByTestId('conversation-operation-b')).toBeVisible();
  expect(screen.getByRole('region', { name: '会话操作' })).toBeVisible();
  expect(screen.getByRole('region', { name: '危险操作' })).toBeVisible();

  fireEvent.click(screen.getByRole('button', { name: '重命名对话' }));
  const input = screen.getByRole('textbox', { name: '重命名当前对话' });
  expect(input).toHaveFocus();
  fireEvent.change(input, { target: { value: '恢复跑安排' } });
  fireEvent.click(screen.getByRole('button', { name: '保存名称' }));

  await screen.findByRole('heading', { name: '恢复跑安排' });
  expect(mutations()).toEqual([
    ['cloud.renameConversation', { conversationId: id, title: '恢复跑安排' }],
  ]);

  fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
  expect(screen.getByRole('region', { name: '确认删除对话' })).toBeVisible();
  expect(screen.getByRole('button', { name: '取消删除' })).toHaveFocus();
});

it.each([false, true])(
  '同名会话取消删除按稳定身份回到原行并保留滚动，归档=%s',
  async (archived) => {
    listing = {
      ...listing,
      conversations: [
        { id, title: '同名合成对话', archived, updatedAtMillis: 1000 },
        { id: archivedId, title: '同名合成对话', archived, updatedAtMillis: 1000 },
      ],
    };
    mount();
    await screen.findByText(row.answer!);
    if (archived) {
      openManagement();
      fireEvent.click(screen.getByRole('button', { name: '已归档对话' }));
    } else openSidebar();
    const target = screen.getAllByRole('button', { name: '管理对话：同名合成对话' })[1];
    target.closest('ul')!.parentElement!.scrollTop = 280;
    fireEvent.click(target);
    fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
    fireEvent.click(screen.getByRole('button', { name: '取消删除' }));
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: '管理对话：同名合成对话' })[1]).toHaveFocus(),
    );
    const restored = screen.getAllByRole('button', { name: '管理对话：同名合成对话' })[1];
    expect(restored).toHaveAttribute('data-conversation-action', archivedId);
    expect(restored.closest('ul')!.parentElement!.scrollTop).toBe(280);
    expect(mutations()).toEqual([]);
  },
);

it('同名归档对话阅读返回后按稳定身份恢复来源焦点、搜索与滚动', async () => {
  listing = {
    ...listing,
    conversations: [
      { id, title: '同名合成对话', archived: true, updatedAtMillis: 1000 },
      { id: archivedId, title: '同名合成对话', archived: true, updatedAtMillis: 1000 },
    ],
  };
  mount();
  await screen.findByText(row.answer!);
  openManagement();
  fireEvent.click(screen.getByRole('button', { name: '已归档对话' }));
  fireEvent.change(screen.getByLabelText('搜索已归档会话标题'), { target: { value: '同名' } });
  const source = screen.getAllByRole('button', { name: '打开会话：同名合成对话' })[1];
  source.closest('ul')!.parentElement!.scrollTop = 320;
  fireEvent.click(source);
  await screen.findByRole('region', { name: '归档对话只读' });
  fireEvent.click(screen.getByRole('button', { name: '返回已归档对话' }));
  const restored = screen.getAllByRole('button', {
    name: '打开会话：同名合成对话',
  })[1];
  await waitFor(() => expect(restored).toHaveFocus());
  expect(restored).toHaveAttribute('data-conversation-open', archivedId);
  expect(restored.closest('ul')!.parentElement!.scrollTop).toBe(320);
  expect(screen.getByLabelText('搜索已归档会话标题')).toHaveValue('同名');
  expect(mutations()).toEqual([['cloud.selectConversation', { conversationId: archivedId }]]);
});

it('归档当前对话回到空白，清除其草稿，不创建会话或发送', async () => {
  listing = { ...listing, legacyAvailable: true, legacyArchived: true };
  mount();
  await screen.findByText(row.answer!);
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '不能遗留的草稿' } });
  openSidebar();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：当前合成对话' }));
  fireEvent.click(screen.getByRole('button', { name: '归档对话' }));
  await screen.findByText('已归档，可在设置与管理中查找。');
  fireEvent.click(screen.getByRole('button', { name: '关闭侧边栏' }));
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('');
  expect(screen.queryByText(row.answer!)).not.toBeInTheDocument();
  expect(mutations()).toEqual([['cloud.archiveConversation', { conversationId: id }]]);
});

it('没有旧版记录时不显示空组，已有旧组支持归档、只读打开与恢复', async () => {
  const mounted = mount();
  await screen.findByText(row.answer!);
  openManagement();
  expect(screen.queryByRole('button', { name: '打开会话：旧版历史记录' })).not.toBeInTheDocument();
  mounted.unmount();
  listing = { ...listing, legacyAvailable: true };
  mount();
  await screen.findByText(row.answer!);
  openManagement();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：旧版历史记录' }));
  fireEvent.click(screen.getByRole('button', { name: '归档对话' }));
  await screen.findByText('已归档，可在设置与管理中查找。');
  fireEvent.click(screen.getByRole('button', { name: '已归档对话' }));
  fireEvent.click(screen.getByRole('button', { name: '打开会话：旧版历史记录' }));
  await screen.findByRole('region', { name: '归档对话只读' });
  fireEvent.click(screen.getByRole('button', { name: '恢复对话' }));
  await screen.findByLabelText('给流萤的消息');
  expect(mutations()).toEqual([
    ['cloud.archiveConversation', { conversationId: null }],
    ['cloud.selectConversation', { conversationId: null }],
    ['cloud.restoreConversation', { conversationId: null }],
  ]);
});

it('归档可搜索打开且只读，返回来源管理，显式恢复后才出现输入框', async () => {
  mount();
  await screen.findByText(row.answer!);
  openManagement();
  fireEvent.click(screen.getByRole('button', { name: '已归档对话' }));
  fireEvent.change(screen.getByLabelText('搜索已归档会话标题'), { target: { value: '合成' } });
  fireEvent.click(screen.getByRole('button', { name: '打开会话：已归档合成对话' }));
  await screen.findByRole('region', { name: '归档对话只读' });
  expect(screen.queryByLabelText('给流萤的消息')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '在线执行' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '返回已归档对话' }));
  expect(screen.getByLabelText('搜索已归档会话标题')).toHaveValue('合成');
  fireEvent.click(screen.getByRole('button', { name: '关闭侧边栏' }));
  fireEvent.click(screen.getByRole('button', { name: '恢复对话' }));
  await screen.findByLabelText('给流萤的消息');
  expect(mutations().map(([name]) => name)).toEqual([
    'cloud.selectConversation',
    'cloud.restoreConversation',
  ]);
});

it('确认仅删除所选会话，清理待办可显式重试且不创建或恢复任务', async () => {
  mount();
  await screen.findByText(row.answer!);
  openSidebar();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：当前合成对话' }));
  fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
  fireEvent.click(screen.getByRole('button', { name: '确认永久删除' }));
  await screen.findByText('本机聊天内容已删除。');
  fireEvent.click(screen.getByRole('button', { name: '设置与管理' }));
  expect(screen.getByRole('region', { name: '云端清理待办' })).toHaveTextContent(
    '1 条云端清理待完成',
  );
  fireEvent.click(screen.getByRole('button', { name: '重试清理' }));
  await waitFor(() =>
    expect(screen.queryByRole('region', { name: '云端清理待办' })).not.toBeInTheDocument(),
  );
  expect(mutations()).toEqual([
    ['cloud.deleteConversation', { conversationId: id, confirmed: true }],
    ['cloud.retryConversationCleanup', {}],
  ]);
});

it('旧版组仅在有记录时显示，null 删除不触及独立本机任务', async () => {
  listing = { ...listing, legacyAvailable: true };
  mount();
  await screen.findByText(row.answer!);
  openManagement();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：旧版历史记录' }));
  fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
  fireEvent.click(screen.getByRole('button', { name: '确认永久删除' }));
  await screen.findByText('本机聊天内容已删除。');
  expect(screen.queryByRole('button', { name: '打开会话：旧版历史记录' })).not.toBeInTheDocument();
  expect(mutations()).toEqual([
    ['cloud.deleteConversation', { conversationId: null, confirmed: true }],
  ]);
});

it('原生拒绝删除时保留确认和正文，错误只在管理区域显示', async () => {
  const original = send.getMockImplementation()!;
  send.mockImplementation((name: string, body: unknown) =>
    name === 'cloud.deleteConversation'
      ? Promise.resolve({ status: 'rejected' })
      : original(name, body),
  );
  mount();
  await screen.findByText(row.answer!);
  openSidebar();
  fireEvent.click(screen.getByRole('button', { name: '管理对话：当前合成对话' }));
  fireEvent.click(screen.getByRole('button', { name: '删除对话' }));
  fireEvent.click(screen.getByRole('button', { name: '确认永久删除' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('会话操作未确认完成');
  expect(screen.getByRole('button', { name: '确认永久删除' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '取消删除' }));
  fireEvent.click(screen.getByRole('button', { name: '关闭侧边栏' }));
  expect(screen.getByText(row.answer!)).toBeVisible();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('删除后的用量独立显示待确认状态，协议禁止留存题目和超过100条记录', async () => {
  listing = { ...listing, items: [], retainedUsage: [{ requestId, atMillis: 1000, cost: null }] };
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('region', { name: '删除后保留的必要用量' })).toHaveTextContent(
    '待确认',
  );
  expect(screen.queryByText(row.prompt)).not.toBeInTheDocument();
  expect(
    cloudListingSchema.safeParse({
      ...listing,
      retainedUsage: [{ ...listing.retainedUsage![0], prompt: '禁止正文' }],
    }).success,
  ).toBe(false);
  expect(
    cloudListingSchema.safeParse({
      ...listing,
      retainedUsage: Array.from({ length: 101 }, () => listing.retainedUsage![0]),
    }).success,
  ).toBe(false);
});

it('生命周期命令严格校验目标和显式删除确认，兼容缺失新增字段的列表', () => {
  const accepts = (name: string, body: unknown) =>
    cloudCommandSchemas.some((schema) => schema.safeParse({ helloId: id, name, body }).success);
  for (const conversationId of [id, null]) {
    expect(accepts('cloud.archiveConversation', { conversationId })).toBe(true);
    expect(accepts('cloud.restoreConversation', { conversationId })).toBe(true);
    expect(accepts('cloud.deleteConversation', { conversationId, confirmed: true })).toBe(true);
    expect(accepts('cloud.deleteConversation', { conversationId })).toBe(false);
    expect(accepts('cloud.deleteConversation', { conversationId, confirmed: false })).toBe(false);
  }
  expect(accepts('cloud.archiveConversation', { conversationId: '错误ID' })).toBe(false);
  expect(accepts('cloud.retryConversationCleanup', { ownerId: id })).toBe(false);
  expect(
    cloudListingSchema.safeParse({
      commandId: id,
      configured: true,
      configuring: false,
      origin: listing.origin,
      items: [],
    }).success,
  ).toBe(true);
});
