import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { afterAll, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentPage } from './AgentPage';
import { AgentUsagePage } from '../settings/AgentUsagePage';
import { useKnowledgePreferences } from '../settings/useKnowledgePreferences';
import { cloudCommandSchemas, cloudListingSchema, runSummarySchema } from '../../bridge/cloudAgent';
import mcpFixture from '../../../../contracts/mcp-phone-v1/settings.json';

const send = vi.hoisted(() => vi.fn());
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
beforeAll(() =>
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: vi.fn(),
  }),
);
afterAll(() => {
  if (originalScroll)
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));
const commandId = '11111111-1111-4111-8111-111111111111';
const id = '22222222-2222-4222-8222-222222222222';
const token = '33333333-3333-4333-8333-333333333333';
const origin = 'https://agent.example.com';
const runSummary = {
  runId: 'run-1',
  distanceMeters: 2000,
  durationSeconds: 720,
  averagePaceSecPerKm: 360,
  averageHeartRate: null,
};
const mountWithRun = () =>
  render(
    <MemoryRouter initialEntries={[{ pathname: '/agent', state: { runId: 'run-1' } }]}>
      <AgentPage />
    </MemoryRouter>,
  );
const accepted = (data: unknown) => ({ status: 'accepted', data });
const memory = {
  id,
  version: 1,
  content: '喜欢晚跑',
  source: 'user_explicit',
  createdAtMillis: 1000,
  updatedAtMillis: 1000,
};
const row = {
  requestId: token,
  prompt: '今晚怎么跑',
  busy: false,
  attempted: true,
  status: 'completed',
  resultState: 'acknowledged',
  reason: null,
  answer: '记得你喜欢晚跑，今晚放轻松。',
  pendingControl: null,
  confirmedControl: null,
  error: null,
  memoryRefs: [{ id, version: 1 }],
};
const empty = { commandId, configured: true, configuring: false, origin, items: [] as unknown[] };
const prepared = {
  commandId,
  token,
  origin,
  prompt: '今晚怎么跑',
  memories: [{ id, version: 1, content: '喜欢晚跑' }],
};
let listing = empty;
const mount = () =>
  render(
    <MemoryRouter>
      <AgentPage />
    </MemoryRouter>,
  );

it('回答复制只写剪贴板，聊天区不再显示知识库选择', async () => {
  listing = { ...empty, items: [row] };
  const writeText = vi.fn().mockResolvedValue(undefined);
  const previous = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  try {
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '复制回答' }));
    await screen.findByText('已复制');
    expect(writeText).toHaveBeenCalledWith(row.answer);
    expect(screen.queryByRole('group', { name: '本次知识库' })).not.toBeInTheDocument();
    expect(
      send.mock.calls.some(([name]) => name === 'cloud.prepare' || name === 'cloud.confirm'),
    ).toBe(false);
  } finally {
    if (previous) Object.defineProperty(navigator, 'clipboard', previous);
    else Reflect.deleteProperty(navigator, 'clipboard');
  }
});

it('播放按钮在复制右侧，读取原生播放状态并停止，不发送聊天请求', async () => {
  const speech = { requestId: null as string | null, state: 'idle', error: null, available: true };
  send.mockImplementation(async (name: string, body: { requestId?: string }) => {
    if (name === 'cloud.speak')
      Object.assign(speech, { requestId: body.requestId, state: 'preparing' });
    if (name === 'cloud.stopSpeech') speech.state = 'idle';
    if (['cloud.list', 'cloud.speak', 'cloud.stopSpeech'].includes(name))
      return accepted({ ...empty, items: [row], speech: { ...speech } });
    return accepted({ commandId, items: [] });
  });
  mount();
  const play = await screen.findByRole('button', { name: '播放回答' });
  expect(play.previousElementSibling).toHaveAttribute('aria-label', '复制回答');
  fireEvent.click(play);
  fireEvent.click(await screen.findByRole('button', { name: '停止播放' }));
  await screen.findByRole('button', { name: '播放回答' });
  expect(send).toHaveBeenCalledWith('cloud.speak', { requestId: token });
  expect(send).toHaveBeenCalledWith('cloud.stopSpeech', {});
  expect(
    send.mock.calls.some(([name]) =>
      ['cloud.send', 'cloud.confirm', 'cloud.prepare'].includes(name),
    ),
  ).toBe(false);
});

it('原生增量通知只刷新本机草稿，工具轮可清空，终态替换为正式回答且不发送问题', async () => {
  let current = {
    ...row,
    answer: null as string | null,
    draftAnswer: '合成前缀' as string | undefined,
    busy: true,
    status: 'running',
    resultState: 'none',
    memoryRefs: [],
  };
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list') return accepted({ ...empty, items: [current] });
    if (name === 'agent.list') return accepted({ commandId, items: [] });
    return accepted({ commandId });
  });
  mount();
  await screen.findByText('合成前缀');
  expect(screen.getByRole('region', { name: '流萤正在回答' })).toHaveAttribute('aria-busy', 'true');
  const notify = () =>
    act(() => useBridgeStore.setState((state) => ({ cloudRevision: state.cloudRevision + 1 })));
  current = { ...current, draftAnswer: '合成前缀，新的片段。' };
  notify();
  await screen.findByText('合成前缀，新的片段。');
  current = { ...current, draftAnswer: undefined };
  notify();
  await waitFor(() => expect(screen.queryByText('合成前缀，新的片段。')).not.toBeInTheDocument());
  current = {
    ...current,
    busy: false,
    status: 'completed',
    resultState: 'available',
    answer: '最终确认回答。',
  };
  notify();
  await screen.findByText('最终确认回答。');
  expect(screen.queryByRole('region', { name: '流萤正在回答' })).not.toBeInTheDocument();
  expect(send.mock.calls.every(([name]) => ['cloud.list', 'agent.list'].includes(name))).toBe(true);
});

it('撤回、失败、空闲和已完成的回执不能携带临时草稿', () => {
  const draft = {
    ...row,
    answer: null,
    draftAnswer: '暂存文字',
    busy: true,
    status: 'running',
    resultState: 'none',
  };
  expect(cloudListingSchema.safeParse({ ...empty, items: [draft] }).success).toBe(true);
  for (const change of [
    { busy: false },
    { status: 'failed' },
    { status: 'completed' },
    { pendingControl: 'withdraw' },
    { confirmedControl: 'withdraw' },
    { answer: '正式回答' },
  ])
    expect(
      cloudListingSchema.safeParse({ ...empty, items: [{ ...draft, ...change }] }).success,
    ).toBe(false);
});

it('页面退后台立即清除临时文字，回前台重新读取，读取失败不能留下旧草稿', async () => {
  let current = {
    ...row,
    answer: null,
    draftAnswer: '后台前的临时文字',
    busy: true,
    status: 'running',
    resultState: 'none',
    memoryRefs: [],
  };
  let failRead = false;
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list') {
      if (failRead) throw new Error('合成读取失败');
      return accepted({ ...empty, items: [current] });
    }
    if (name === 'agent.list') return accepted({ commandId, items: [] });
    throw new Error('禁止其他命令');
  });
  mount();
  await screen.findByText('后台前的临时文字');
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  try {
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(screen.queryByText('后台前的临时文字')).not.toBeInTheDocument();
    hidden.mockReturnValue(false);
    current = { ...current, draftAnswer: '回前台新读取的文字' };
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    await screen.findByText('回前台新读取的文字');
    failRead = true;
    act(() => useBridgeStore.setState((state) => ({ cloudRevision: state.cloudRevision + 1 })));
    await waitFor(() => expect(screen.queryByText('回前台新读取的文字')).not.toBeInTheDocument());
    expect(send.mock.calls.every(([name]) => ['cloud.list', 'agent.list'].includes(name))).toBe(
      true,
    );
  } finally {
    hidden.mockRestore();
  }
});

it.each([
  { authorized: true, both: false },
  { authorized: false, both: false },
  { authorized: true, both: true },
  { authorized: false, both: true },
])(
  '持续授权 $authorized、双库 $both 时按相应入口发送，重复点击不重复提交',
  async ({ authorized, both }) => {
    useKnowledgePreferences.setState({ health: both, firefly: both, allowSpoilers: false });
    const knowledge = both
      ? { version: 'curated_knowledge_v1', library: 'both', allowSpoilers: false }
      : undefined;
    const active = {
      ...empty,
      chatAuthorized: authorized,
      chatConsentContext: 'a'.repeat(64),
      conversationId: id,
      conversations: [{ id, title: '夜跑', updatedAtMillis: 1000 }],
    };
    let finish!: (value: unknown) => void;
    send.mockImplementation(async (name: string) => {
      if (name === 'cloud.list') return accepted(active);
      if (name === 'agent.list')
        return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
      if (name === 'cloud.prepare')
        return accepted({
          ...prepared,
          memories: [],
          ...(knowledge ? { knowledge } : {}),
          conversation: { version: 'conversation_v1', id, approved: true, messages: [] },
        });
      if (name === 'cloud.send')
        return new Promise((resolve) => {
          finish = resolve;
        });
      throw new Error('合成测试禁止其他命令');
    });
    mount();
    await screen.findByText(
      authorized ? '持续授权已开启，普通文字点击即发送' : '在线发送前会再次确认',
    );
    fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: prepared.prompt } });
    fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
    await waitFor(() =>
      expect(send).toHaveBeenCalledWith('cloud.prepare', {
        prompt: prepared.prompt,
        memoryRefs: [],
        conversationId: id,
        ...(knowledge ? { knowledge } : {}),
      }),
    );
    if (authorized) {
      await waitFor(() => expect(send).toHaveBeenCalledWith('cloud.send', { token }));
      fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
      expect(send.mock.calls.filter(([name]) => name === 'cloud.send')).toHaveLength(1);
      await act(async () => finish(accepted({ ...active, items: [{ ...row, memoryRefs: [] }] })));
      await waitFor(() => expect(screen.getByLabelText('给流萤的消息')).toHaveValue(''));
      expect(screen.getByText(row.answer)).toBeVisible();
    } else {
      await screen.findByRole('button', { name: '同意本次上传并发送' });
      expect(screen.getByLabelText('给流萤的消息')).toHaveValue(prepared.prompt);
    }
    expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
  },
);

it('即使持续授权已开启，跑步摘要仍停在单次确认，不走直发', async () => {
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list') return accepted({ ...empty, chatAuthorized: true });
    if (name === 'agent.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
    if (name === 'cloud.prepare')
      return accepted({
        ...prepared,
        prompt: '请根据本次跑步摘要帮我复盘，说明数据能支持的结论和不足，不做医学诊断。',
        memories: [],
        runSummary,
      });
    throw new Error('不应直发');
  });
  mountWithRun();
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  await screen.findByRole('button', { name: '同意本次上传并发送' });
  expect(send.mock.calls.some(([name]) => ['cloud.send', 'cloud.confirm'].includes(name))).toBe(
    false,
  );
});

it('持续授权状态过期时原生拒绝直发，草稿保留且不降级为自动确认或重发', async () => {
  const active = { ...empty, chatAuthorized: true, conversationId: id };
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list') return accepted(active);
    if (name === 'agent.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
    if (name === 'cloud.prepare')
      return accepted({
        ...prepared,
        memories: [],
        conversation: { version: 'conversation_v1', id, approved: true, messages: [] },
      });
    if (name === 'cloud.send') return { status: 'rejected' };
    if (name === 'cloud.discard') return accepted({ commandId, discarded: true });
    throw new Error('不应自动重试');
  });
  mount();
  await screen.findByText('持续授权已开启，普通文字点击即发送');
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: prepared.prompt } });
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('发送未确认');
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue(prepared.prompt);
  expect(send.mock.calls.filter(([name]) => name === 'cloud.send')).toHaveLength(1);
  expect(
    send.mock.calls.some(([name]) =>
      ['cloud.confirm', 'cloud.chatConsent', 'cloud.recover'].includes(name),
    ),
  ).toBe(false);
});

it('侧栏按会话切换而不是按消息跳转，草稿分别保留且新会话不继承旧记录', async () => {
  let active = id;
  const conversations = [
    { id, title: '夜跑安排', updatedAtMillis: 1000 },
    { id: token, title: '周末计划', updatedAtMillis: 2000 },
  ];
  const current = () => ({
    ...empty,
    conversationId: active,
    conversations,
    items: active === id ? [row] : [],
  });
  send.mockImplementation(async (name: string, body: unknown) => {
    if (name === 'cloud.list') return accepted(current());
    if (name === 'cloud.selectConversation') {
      active = (body as { conversationId: string }).conversationId;
      return accepted(current());
    }
    if (name === 'cloud.newConversation') {
      active = commandId;
      return accepted(current());
    }
    if (name === 'agent.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
    throw new Error('本测试不应发送网络请求');
  });
  mount();
  await screen.findByText(row.answer);
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '旧会话草稿' } });
  fireEvent.click(screen.getByRole('button', { name: '打开侧边栏' }));
  fireEvent.click(await screen.findByRole('button', { name: '打开会话：周末计划' }));
  await waitFor(() => expect(screen.getByLabelText('给流萤的消息')).toHaveValue(''));
  expect(screen.queryByText(row.answer)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '打开侧边栏' }));
  fireEvent.click(screen.getByRole('button', { name: '打开会话：夜跑安排' }));
  await waitFor(() => expect(screen.getByLabelText('给流萤的消息')).toHaveValue('旧会话草稿'));
  expect(screen.getByText(row.answer)).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '打开侧边栏' }));
  fireEvent.click(screen.getByRole('button', { name: '新建对话' }));
  await waitFor(() => expect(screen.getByLabelText('给流萤的消息')).toHaveValue(''));
  expect(screen.queryByText(row.answer)).not.toBeInTheDocument();
  expect(
    send.mock.calls.some(([name]) => name === 'cloud.prepare' || name === 'cloud.confirm'),
  ).toBe(false);
});

it('发送当前会话标识并预览完整上一轮，不把上下文当作新问题', async () => {
  const messages = [
    { role: 'user', text: '我喜欢夜跑' },
    { role: 'assistant', text: '我们按晚间安排' },
  ];
  send.mockImplementation(async (name: string, body: unknown) => {
    if (name === 'cloud.list')
      return accepted({
        ...empty,
        conversationId: id,
        conversations: [{ id, title: '夜跑', updatedAtMillis: 1000 }],
      });
    if (name === 'cloud.prepare') {
      expect(body).toEqual({ prompt: prepared.prompt, memoryRefs: [], conversationId: id });
      return accepted({
        ...prepared,
        memories: [],
        conversation: { version: 'conversation_v1', id, approved: true, messages },
      });
    }
    if (name === 'agent.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
    return accepted({});
  });
  mount();
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeDisabled());
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: prepared.prompt } });
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  expect(await screen.findByText('本次会话上下文（1 轮）')).toBeInTheDocument();
  expect(screen.getByText('你：我喜欢夜跑')).toBeInTheDocument();
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
});
beforeEach(() => {
  send.mockReset();
  useKnowledgePreferences.setState({ health: false, firefly: false, allowSpoilers: false });
  listing = empty;
  useBridgeStore.setState({ status: 'ready', snapshot: null });
  send.mockImplementation(async (name: string, body: unknown) => {
    if (name === 'cloud.list') return accepted(listing);
    if (name === 'memory.list')
      return accepted({ commandId, offset: 0, limit: 20, totalItems: 1, items: [memory] });
    if (name === 'cloud.prepare') {
      const refs = (body as { memoryRefs: unknown[] }).memoryRefs;
      const knowledge = (body as { knowledge?: unknown }).knowledge;
      return accepted({
        ...prepared,
        memories: refs.length ? prepared.memories : [],
        ...(knowledge ? { knowledge } : {}),
      });
    }
    if (name === 'cloud.confirm') {
      listing = { ...empty, items: [row] };
      return accepted(listing);
    }
    if (name === 'cloud.recover') return accepted(listing);
    if (name === 'cloud.discard') return accepted({ commandId, discarded: true });
    if (name === 'cloud.withdraw') {
      listing = { ...empty, items: [{ ...row, answer: null, pendingControl: 'withdraw' }] };
      return accepted(listing);
    }
    return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
  });
});
async function openReferencePicker() {
  if (!screen.queryByRole('dialog', { name: '输入选项' })) {
    fireEvent.click(screen.getByRole('button', { name: '更多输入选项' }));
    await screen.findByRole('dialog', { name: '输入选项' });
  }
  fireEvent.click(screen.getByText('本次参考'));
}

async function authorize(withMemory = true) {
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  if (withMemory) {
    await openReferencePicker();
    fireEvent.click(screen.getByRole('button', { name: '选择本次记忆' }));
    fireEvent.click(await screen.findByLabelText(/喜欢晚跑/));
  }
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  return screen.findByRole('region', { name: '本次云端授权' });
}

it('模型自称记住了但没有原生保存回执时不显示保存提示', async () => {
  listing = { ...empty, items: [{ ...row, answer: '我记住了。' }] };
  mount();
  await screen.findByText('我记住了。');
  expect(screen.queryByRole('link', { name: '记忆已保存，管理记忆' })).not.toBeInTheDocument();
});

it.each(['completed', 'failed'])(
  '原生确认保存后显示管理入口，最终回答 %s 不改变保存事实',
  async (status) => {
    listing = {
      ...empty,
      items: [
        { ...row, status, answer: status === 'completed' ? row.answer : null, memorySaved: true },
      ],
    };
    const router = createMemoryRouter([
      { path: '/', element: <AgentPage /> },
      { path: '/memories', element: <h1>合成记忆管理页</h1> },
    ]);
    render(<RouterProvider router={router} />);
    fireEvent.click(await screen.findByRole('link', { name: '记忆已保存，管理记忆' }));
    expect(await screen.findByRole('heading', { name: '合成记忆管理页' })).toBeVisible();
    expect(send.mock.calls.every(([name]) => ['cloud.list', 'agent.list'].includes(name))).toBe(
      true,
    );
  },
);

it.each([
  { pendingControl: 'withdraw' },
  { confirmedControl: 'withdraw' },
  { resultState: 'withdrawn' },
])('撤回消息不显示旧的保存提示：%j', async (control) => {
  listing = { ...empty, items: [{ ...row, ...control, memorySaved: true }] };
  mount();
  await screen.findByText(row.prompt);
  expect(screen.queryByRole('link', { name: '记忆已保存，管理记忆' })).not.toBeInTheDocument();
});

it('保存提示只接受原生可选 true，不接受模型字符串或计数', () => {
  expect(cloudListingSchema.safeParse({ ...empty, items: [row] }).success).toBe(true);
  expect(
    cloudListingSchema.safeParse({ ...empty, items: [{ ...row, memorySaved: true }] }).success,
  ).toBe(true);
  for (const memorySaved of [false, 'true', 1, null, { saved: true }]) {
    expect(
      cloudListingSchema.safeParse({ ...empty, items: [{ ...row, memorySaved }] }).success,
    ).toBe(false);
  }
});

it('刷新发现原生回执已失效时移除保存入口而不隐藏回答', async () => {
  listing = { ...empty, items: [{ ...row, memorySaved: true }] };
  mount();
  await screen.findByRole('link', { name: '记忆已保存，管理记忆' });
  listing = { ...empty, items: [row] };
  fireEvent.click(screen.getByText('更多操作'));
  fireEvent.click(screen.getByRole('button', { name: '恢复查询' }));
  await waitFor(() =>
    expect(screen.queryByRole('link', { name: '记忆已保存，管理记忆' })).not.toBeInTheDocument(),
  );
  expect(screen.getByText(row.answer)).toBeVisible();
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm' || name === 'cloud.send')).toBe(
    false,
  );
});

it('首次进入只读本机列表，不提取记忆、不自动联网执行', async () => {
  mount();
  await screen.findByText('在线发送前会再次确认');
  expect(send.mock.calls.map(([name]) => name).sort()).toEqual(['agent.list', 'cloud.list']);
});

it('跑后入口仅携带ID，单次确认才发送，成功后不把概要带入下一次', async () => {
  const original = send.getMockImplementation()!;
  send.mockImplementation(async (name: string, body: { runId?: string }) =>
    name === 'cloud.prepare'
      ? accepted({ ...prepared, memories: [], ...(body.runId ? { runSummary } : {}) })
      : original(name, body),
  );
  mountWithRun();
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue(
    '请根据本次跑步摘要帮我复盘，说明数据能支持的结论和不足，不做医学诊断。',
  );
  expect(screen.getByText('已选择一条跑步摘要，发送前核对')).toBeInTheDocument();
  expect(
    send.mock.calls.some(([name]) => name === 'cloud.prepare' || name === 'cloud.confirm'),
  ).toBe(false);
  const preview = await authorize(false);
  expect(send).toHaveBeenCalledWith('cloud.prepare', {
    prompt: '今晚怎么跑',
    memoryRefs: [],
    runId: 'run-1',
  });
  expect(within(preview).getByRole('region', { name: '本次授权跑步摘要' })).toHaveTextContent(
    '2000 米',
  );
  expect(within(preview).getByText(/未采集/)).toBeInTheDocument();
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
  fireEvent.click(within(preview).getByRole('button', { name: '同意本次上传并发送' }));
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '移除跑步摘要' })).not.toBeInTheDocument(),
  );
  await authorize(false);
  expect(send).toHaveBeenLastCalledWith('cloud.prepare', { prompt: '今晚怎么跑', memoryRefs: [] });
  expect(send.mock.calls.filter(([name]) => name === 'cloud.confirm')).toHaveLength(1);
});

it('移除跑步摘要不上传，旧原生忽略ID时拒绝授权而非静默降级', async () => {
  const page = mountWithRun();
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('跑步记录');
  expect(screen.queryByRole('region', { name: '本次云端授权' })).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('cloud.discard', { token });
  fireEvent.click(screen.getByRole('button', { name: '移除跑步摘要' }));
  await authorize(false);
  expect(send).toHaveBeenCalledWith('cloud.prepare', { prompt: '今晚怎么跑', memoryRefs: [] });
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
  page.unmount();
});

it('入口状态只消费一次，返回对话页不重新附带旧跑步或默认问题', async () => {
  const router = createMemoryRouter(
    [
      { path: '/agent', element: <AgentPage /> },
      { path: '/other', element: <div>其他页</div> },
    ],
    { initialEntries: [{ pathname: '/agent', state: { runId: 'run-1' } }] },
  );
  render(<RouterProvider router={router} />);
  expect(screen.getByRole('button', { name: '移除跑步摘要' })).toBeInTheDocument();
  await waitFor(() => expect(router.state.location.state).toBeNull());
  await act(async () => {
    await router.navigate('/other');
  });
  await act(async () => {
    await router.navigate(-1);
  });
  expect(screen.queryByRole('button', { name: '移除跑步摘要' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('');
  expect(
    send.mock.calls.some(([name]) => name === 'cloud.prepare' || name === 'cloud.confirm'),
  ).toBe(false);
});

it('未选择或选错记录的原生响应不能扩大本次授权', async () => {
  const original = send.getMockImplementation()!;
  send.mockImplementation(async (name: string, body: unknown) =>
    name === 'cloud.prepare'
      ? accepted({ ...prepared, memories: [], runSummary: { ...runSummary, runId: 'other' } })
      : original(name, body),
  );
  mountWithRun();
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('没有发送');
  expect(send).toHaveBeenCalledWith('cloud.discard', { token });
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
});

it('单条摘要严格拒绝伪造字段且撤回不再展示授权快照', async () => {
  expect(runSummarySchema.safeParse(runSummary).success).toBe(true);
  for (const value of [
    { ...runSummary, route: [] },
    { ...runSummary, durationSeconds: 0 },
    { ...runSummary, averageHeartRate: 0 },
  ])
    expect(runSummarySchema.safeParse(value).success).toBe(false);
  expect(
    cloudCommandSchemas.some(
      (schema) =>
        schema.safeParse({
          helloId: commandId,
          name: 'cloud.prepare',
          body: { prompt: '复盘', memoryRefs: [], runSummary },
        }).success,
    ),
  ).toBe(false);
  listing = { ...empty, items: [{ ...row, runSummary }] };
  const page = mount();
  expect(await screen.findByRole('region', { name: '本次授权跑步摘要' })).toHaveTextContent(
    '2000 米',
  );
  page.unmount();
  listing = { ...empty, items: [{ ...row, runSummary, pendingControl: 'withdraw', answer: null }] };
  mount();
  await screen.findByRole('article', { name: '在线任务' });
  expect(screen.queryByRole('region', { name: '本次授权跑步摘要' })).not.toBeInTheDocument();
});

it('成本估算展示旧费率与已确认用量，重载保留且不触发请求', async () => {
  listing = {
    ...empty,
    items: [
      {
        ...row,
        cost: {
          currency: 'USD',
          estimatedMicros: 78,
          inputTokens: 100,
          outputTokens: 40,
          usageComplete: true,
          rateStatus: 'stale',
        },
      },
    ],
  };
  const page = render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: /今晚怎么跑/ }));
  expect(await screen.findByText(/\$0\.000078 USD/)).toBeInTheDocument();
  expect(screen.getByText(/沿用上次核验费率/)).toBeInTheDocument();
  expect(screen.getByText(/输入 100 \/ 输出 40 token/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '用户须知' })).toHaveAttribute(
    'href',
    '/settings/notices',
  );
  page.unmount();
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: /今晚怎么跑/ }));
  expect(await screen.findByText(/\$0\.000078 USD/)).toBeInTheDocument();
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
});

it('未知用量费用显示待确认而非零，严格拒绝伪造费用', async () => {
  const cost = {
    currency: 'USD',
    estimatedMicros: null,
    inputTokens: 0,
    outputTokens: 0,
    usageComplete: false,
    rateStatus: 'reference',
  };
  listing = { ...empty, items: [{ ...row, cost }] };
  render(
    <MemoryRouter>
      <AgentUsagePage />
    </MemoryRouter>,
  );
  expect(await screen.findByText('待确认')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /今晚怎么跑/ }));
  expect(screen.getByText('部分用量待确认，不代表零费用。')).toBeInTheDocument();
  expect(screen.getByText(/使用内置参考费率/)).toBeInTheDocument();
  expect(screen.queryByText(/\$0\.000000/)).not.toBeInTheDocument();
  expect(
    cloudListingSchema.safeParse({
      ...empty,
      items: [{ ...row, cost: { ...cost, estimatedMicros: 0 } }],
    }).success,
  ).toBe(false);
});

it('云端已确认撤回后不重新显示费用待确认提示', async () => {
  listing = {
    ...empty,
    items: [{ ...row, answer: null, resultState: 'withdrawn', confirmedControl: 'withdraw' }],
  };
  mount();
  await screen.findByRole('article', { name: '在线任务' });
  expect(screen.queryByRole('region', { name: '本次费用估算' })).not.toBeInTheDocument();
});

it('知识库沿用设置，确认后不会重置用户偏好', async () => {
  useKnowledgePreferences.setState({ health: false, firefly: true, allowSpoilers: true });
  mount();
  const region = await authorize(false);
  expect(region).toHaveTextContent('流萤官方资料 · 允许身份与身世剧透');
  expect(screen.queryByLabelText('知识库')).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('cloud.prepare', {
    prompt: '今晚怎么跑',
    memoryRefs: [],
    knowledge: { version: 'curated_knowledge_v1', library: 'firefly', allowSpoilers: true },
  });
  fireEvent.click(within(region).getByRole('button', { name: '同意本次上传并发送' }));
  await screen.findByRole('region', { name: '流萤的回答' });
  expect(useKnowledgePreferences.getState().allowSpoilers).toBe(true);
});

it('准备响应不能静默扩大剧透权限', async () => {
  useKnowledgePreferences.setState({ health: false, firefly: true, allowSpoilers: false });
  mount();
  await screen.findByText('在线发送前会再次确认');
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  send.mockResolvedValueOnce(
    accepted({
      ...prepared,
      memories: [],
      knowledge: {
        version: 'curated_knowledge_v1',
        library: 'firefly',
        allowSpoilers: true,
      },
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  await screen.findByText(/知识库或插件选择核对失败/);
  expect(send).toHaveBeenCalledWith('cloud.discard', { token });
  expect(screen.queryByRole('region', { name: '本次云端授权' })).not.toBeInTheDocument();
});

it('来源及任务选择重载保留，纯文本地址不允许注入，撤回同时隐藏来源', async () => {
  const source = {
    citationId: 'official_v1_1',
    title: '流萤官网',
    url: 'https://hsr.hoyoverse.com/zh-tw/character',
    publishedAt: null,
    checkedAtMillis: Date.UTC(2026, 8, 23),
  };
  const stored = {
    ...row,
    knowledge: { version: 'curated_knowledge_v1', library: 'firefly', allowSpoilers: false },
    sources: [source],
  };
  listing = { ...empty, items: [stored] };
  const first = mount();
  fireEvent.click(await screen.findByLabelText('查看来源 1'));
  expect(await screen.findByRole('region', { name: '知识来源' })).toHaveTextContent(source.url);
  first.unmount();
  mount();
  fireEvent.click(await screen.findByLabelText('查看来源 1'));
  const region = await screen.findByRole('region', { name: '知识来源' });
  expect(region).toHaveTextContent('发布：未提供');
  expect(region).toHaveTextContent('2026-09-23');
  expect(region.querySelector('a')).toBeNull();
  expect(
    cloudListingSchema.safeParse({
      ...empty,
      items: [{ ...stored, sources: [{ ...source, url: 'javascript:alert(1)' }] }],
    }).success,
  ).toBe(false);
  fireEvent.click(screen.getByText('更多操作'));
  fireEvent.click(screen.getByRole('button', { name: '撤回回答' }));
  fireEvent.click(screen.getByRole('button', { name: '确认撤回' }));
  await waitFor(() =>
    expect(screen.queryByRole('region', { name: '知识来源' })).not.toBeInTheDocument(),
  );
});
it('核对精确内容且取消授权不发送', async () => {
  mount();
  const region = await authorize();
  expect(within(region).getByText(/喜欢晚跑/)).toBeVisible();
  expect(within(region).getByText('今晚怎么跑')).toBeVisible();
  expect(send).toHaveBeenCalledWith('cloud.prepare', {
    prompt: '今晚怎么跑',
    memoryRefs: [{ id, version: 1 }],
  });
  fireEvent.click(within(region).getByRole('button', { name: '暂不发送' }));
  expect(send).toHaveBeenCalledWith('cloud.discard', { token });
  expect(send.mock.calls.some(([name]) => name === 'cloud.confirm')).toBe(false);
  expect(screen.queryByRole('region', { name: '本次云端授权' })).not.toBeInTheDocument();
});
it('连点确认只发送一次并展示回答及记忆来源版本', async () => {
  mount();
  const region = await authorize();
  const confirm = within(region).getByRole('button', { name: '同意本次上传并发送' });
  fireEvent.click(confirm);
  fireEvent.click(confirm);
  const answer = await screen.findByRole('region', { name: '流萤的回答' });
  expect(answer).toHaveTextContent(row.answer);
  expect(send.mock.calls.filter(([name]) => name === 'cloud.confirm')).toEqual([
    ['cloud.confirm', { token, approved: true }],
  ]);
  fireEvent.click(screen.getByText('更多操作'));
  expect(screen.getByText(/不代表模型全部采用/)).toBeVisible();
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('');
});
it('不选记忆可以单次发送，准备结果不能偷偷加记忆', async () => {
  mount();
  const region = await authorize(false);
  expect(region).toHaveTextContent('本次记忆（0 条）');
  expect(send).toHaveBeenCalledWith('cloud.prepare', { prompt: '今晚怎么跑', memoryRefs: [] });
});
it('授权失效时保留草稿，既不补发也不降级本机保存', async () => {
  mount();
  const region = await authorize();
  send.mockRejectedValueOnce(new Error('合成断流'));
  fireEvent.click(within(region).getByRole('button', { name: '同意本次上传并发送' }));
  await screen.findByText(/操作未确认完成/);
  expect(screen.getByLabelText('给流萤的消息')).toHaveValue('今晚怎么跑');
  expect(screen.queryByRole('region', { name: '本次云端授权' })).not.toBeInTheDocument();
  expect(send.mock.calls.filter(([name]) => name === 'cloud.confirm')).toHaveLength(1);
  expect(send.mock.calls.some(([name]) => name === 'agent.submit')).toBe(false);
});
it('页面重载后显示保存的回答，恢复仅调用原请求查询', async () => {
  listing = { ...empty, items: [row] };
  const first = mount();
  await screen.findByRole('region', { name: '流萤的回答' });
  first.unmount();
  mount();
  await screen.findByRole('region', { name: '流萤的回答' });
  fireEvent.click(screen.getByText('更多操作'));
  fireEvent.click(screen.getByRole('button', { name: '恢复查询' }));
  await waitFor(() => expect(send).toHaveBeenCalledWith('cloud.recover', { requestId: token }));
  expect(send.mock.calls.some(([name]) => ['cloud.prepare', 'cloud.confirm'].includes(name))).toBe(
    false,
  );
});
it('查询中断不继续显示正在思考，直接恢复只查询同一任务', async () => {
  listing = {
    ...empty,
    items: [
      {
        ...row,
        status: 'running',
        resultState: 'none',
        answer: null,
        error: '连接或读取在线服务超时。可恢复查询原任务。',
      },
    ],
  };
  mount();
  await screen.findByText('查询已中断，结果待同步');
  expect(screen.queryByText('流萤正在思考')).not.toBeInTheDocument();
  const recovery = screen.getByRole('button', { name: '恢复查询' });
  expect(recovery).toBeVisible();
  fireEvent.click(recovery);
  await waitFor(() => expect(send).toHaveBeenCalledWith('cloud.recover', { requestId: token }));
  expect(
    send.mock.calls.some(([name]) =>
      ['cloud.prepare', 'cloud.confirm', 'agent.submit'].includes(name),
    ),
  ).toBe(false);
});
it('未授权跑步记录显示操作指引，技术原因仅在详情内可见', async () => {
  listing = {
    ...empty,
    items: [{ ...row, status: 'blocked', answer: null, reason: 'scope_denied' }],
  };
  mount();
  expect(
    await screen.findByText(
      '本次未获准读取这条跑步记录。请前往“跑步记录”，选择一条记录后点击“让流萤复盘本次跑步”。',
    ),
  ).toBeVisible();
  const reason = screen.getByText('原因：scope_denied');
  expect(reason).not.toBeVisible();
  fireEvent.click(screen.getByText('更多操作'));
  expect(reason).toBeVisible();
  expect(send.mock.calls.some(([name]) => ['cloud.prepare', 'cloud.confirm'].includes(name))).toBe(
    false,
  );
});
it('失败仍可见，但技术原因收进更多操作', async () => {
  listing = {
    ...empty,
    items: [{ ...row, status: 'failed', answer: null, reason: 'invalid_model_output' }],
  };
  mount();
  expect(
    await screen.findByText('这次没能完成回答，可在“更多操作”中查看原因或恢复查询。'),
  ).toBeVisible();
  expect(screen.getByText('原因：invalid_model_output')).not.toBeVisible();
  fireEvent.click(screen.getByText('更多操作'));
  expect(screen.getByText('原因：invalid_model_output')).toBeVisible();
});
it('正常聊天不展示成功标签、费用、token 或逐条通用说明', async () => {
  listing = {
    ...empty,
    items: [
      {
        ...row,
        cost: {
          currency: 'USD',
          estimatedMicros: 78,
          inputTokens: 100,
          outputTokens: 40,
          usageComplete: true,
          rateStatus: 'current',
        },
      },
    ],
  };
  mount();
  expect(await screen.findByText(row.answer)).toBeVisible();
  expect(screen.queryByText('已完成', { exact: true })).not.toBeInTheDocument();
  expect(screen.queryByText(/USD|token|本次成本估算|不是供应商账单/)).not.toBeInTheDocument();
  expect(screen.queryByText('本次详情与用量')).not.toBeInTheDocument();
  expect(screen.getByText('在线发送前会再次确认')).toHaveClass('sr-only');
});
it('撤回必须二次确认且本机立即隐藏但不谎称云端已撤回', async () => {
  listing = { ...empty, items: [row] };
  mount();
  await screen.findByRole('region', { name: '流萤的回答' });
  fireEvent.click(screen.getByText('更多操作'));
  fireEvent.click(screen.getByRole('button', { name: '撤回回答' }));
  expect(send.mock.calls.some(([name]) => name === 'cloud.withdraw')).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: '确认撤回' }));
  await screen.findByText(/云端控制请求待确认/);
  expect(screen.queryByRole('region', { name: '流萤的回答' })).not.toBeInTheDocument();
});
it('准备结果迟到且草稿改变，旧授权被废弃', async () => {
  let resolve!: (value: unknown) => void;
  mount();
  await screen.findByText('在线发送前会再次确认');
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  send.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  act(() => useBridgeStore.setState({ status: 'error', snapshot: null }));
  await act(async () => resolve(accepted({ ...prepared, memories: [] })));
  expect(screen.queryByRole('region', { name: '本次云端授权' })).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledWith('cloud.discard', { token });
});
it('协议拒绝假授权、正文和凭据偷渡，回答按纯文本展示', async () => {
  expect(
    cloudCommandSchemas.some(
      (schema) =>
        schema.safeParse({
          helloId: commandId,
          name: 'cloud.confirm',
          body: { token, approved: 'true' },
        }).success,
    ),
  ).toBe(false);
  expect(
    cloudCommandSchemas.some(
      (schema) =>
        schema.safeParse({
          helloId: commandId,
          name: 'cloud.prepare',
          body: { prompt: '问题', memoryRefs: [], bearer: '秘密' },
        }).success,
    ),
  ).toBe(false);
  expect(cloudListingSchema.safeParse({ ...empty, secret: '秘密' }).success).toBe(false);
  listing = { ...empty, items: [{ ...row, answer: '<img src=x onerror=alert(1)>' }] };
  mount();
  const answer = await screen.findByRole('region', { name: '流萤的回答' });
  expect(answer).toHaveTextContent('<img src=x onerror=alert(1)>');
  expect(answer.querySelector('img')).toBeNull();
});

it.each([false, true])('选择插件时必须单次预览，返回范围不符 %s 时不发送', async (mismatch) => {
  const active = {
    ...empty,
    chatAuthorized: true,
    chatConsentContext: 'a'.repeat(64),
    conversationId: id,
    conversations: [],
  };
  send.mockImplementation(async (name: string) => {
    if (name === 'cloud.list' || name === 'cloud.confirm') return accepted(active);
    if (name === 'mcp.status') return accepted(mcpFixture.status);
    if (name === 'mcp.list')
      return accepted({
        ...mcpFixture.list,
        services: mcpFixture.list.services.map((row) => ({ ...row, enabled: true })),
      });
    if (name === 'cloud.prepare')
      return accepted({
        ...prepared,
        memories: [],
        conversation: { version: 'conversation_v1', id, approved: true, messages: [] },
        mcpServices: [
          {
            id: mismatch ? id : mcpFixture.list.services[0].id,
            endpoint: mcpFixture.list.services[0].endpoint,
            tools: [
              { name: 'notes.search', mode: 'read' },
              { name: 'notes.write', mode: 'confirm' },
            ],
          },
        ],
      });
    if (name === 'cloud.discard') return accepted({ commandId, discarded: true });
    return accepted({ commandId, offset: 0, limit: 20, totalItems: 0, items: [] });
  });
  mount();
  await screen.findByText('持续授权已开启，普通文字点击即发送');
  fireEvent.click(screen.getByLabelText('更多输入选项'));
  fireEvent.click(screen.getByRole('button', { name: '读取可用插件' }));
  fireEvent.click(await screen.findByRole('checkbox'));
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: prepared.prompt } });
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  if (mismatch) {
    await screen.findByText(/插件选择核对失败/);
    expect(send).toHaveBeenCalledWith('cloud.discard', { token });
  } else {
    const preview = await screen.findByRole('region', { name: '本次插件权限' });
    expect(preview).toHaveTextContent('只读，可自主调用');
    expect(preview).toHaveTextContent('每次展示完整参数');
    expect(send).toHaveBeenCalledWith('cloud.prepare', {
      prompt: prepared.prompt,
      conversationId: id,
      memoryRefs: [],
      mcpServices: [mcpFixture.list.services[0].id],
    });
    fireEvent.click(screen.getByRole('button', { name: '同意本次上传并发送' }));
    await waitFor(() =>
      expect(send).toHaveBeenCalledWith('cloud.confirm', { token, approved: true }),
    );
    fireEvent.click(screen.getByLabelText('更多输入选项'));
    expect(screen.getByRole('checkbox')).not.toBeChecked();
  }
  expect(send.mock.calls.some(([name]) => name === 'cloud.send')).toBe(false);
});

it('插件选择协议拒绝空选择、重复、无会话和伪造原生许可', () => {
  const valid = { prompt: '问题', conversationId: id, memoryRefs: [], mcpServices: [id] };
  const accepts = (body: unknown) =>
    cloudCommandSchemas.some(
      (schema) => schema.safeParse({ helloId: commandId, name: 'cloud.prepare', body }).success,
    );
  expect(accepts(valid)).toBe(true);
  expect(accepts({ ...valid, mcpServices: [] })).toBe(false);
  expect(accepts({ ...valid, mcpServices: [id, id] })).toBe(false);
  expect(accepts({ ...valid, conversationId: undefined })).toBe(false);
  expect(accepts({ ...valid, mcpServices: [{ serviceId: id, approved: true }] })).toBe(false);
});
