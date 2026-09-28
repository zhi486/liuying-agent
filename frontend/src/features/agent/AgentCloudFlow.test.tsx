import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { AgentPage } from './AgentPage';
import { cloudCommandSchemas, cloudListingSchema } from '../../bridge/cloudAgent';

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
beforeEach(() => {
  send.mockReset();
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
async function authorize(withMemory = true) {
  fireEvent.change(screen.getByLabelText('给流萤的消息'), { target: { value: '今晚怎么跑' } });
  if (withMemory) {
    fireEvent.click(screen.getByText('本次参考'));
    fireEvent.click(screen.getByRole('button', { name: '选择本次记忆' }));
    fireEvent.click(await screen.findByLabelText(/喜欢晚跑/));
  }
  await waitFor(() => expect(screen.getByRole('button', { name: '在线执行' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '在线执行' }));
  return screen.findByRole('region', { name: '本次云端授权' });
}

it('首次进入只读本机列表，不提取记忆、不自动联网执行', async () => {
  mount();
  await screen.findByText('在线发送前会再次确认');
  expect(send.mock.calls.map(([name]) => name).sort()).toEqual(['agent.list', 'cloud.list']);
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
  const page = mount();
  expect(await screen.findByText(/\$0\.000078 USD/)).toBeInTheDocument();
  expect(screen.getByText(/沿用上次核验费率/)).toBeInTheDocument();
  expect(screen.getByText(/输入 100 \/ 输出 40 token/)).toBeInTheDocument();
  expect(screen.getByText(/预算超出不关闭功能/)).toBeInTheDocument();
  page.unmount();
  mount();
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
  mount();
  expect(await screen.findByText('本次成本估算：待确认')).toBeInTheDocument();
  expect(screen.getByText(/不代表零费用/)).toBeInTheDocument();
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

it('知识默认关闭，剧透默认不选，切换库重置且确认期间锁定', async () => {
  mount();
  fireEvent.click(screen.getByText('本次参考'));
  expect(screen.getByLabelText('知识库')).toHaveValue('none');
  fireEvent.change(screen.getByLabelText('知识库'), { target: { value: 'firefly' } });
  const spoiler = screen.getByLabelText('本次允许身份与身世剧透');
  expect(spoiler).not.toBeChecked();
  fireEvent.click(spoiler);
  fireEvent.change(screen.getByLabelText('知识库'), { target: { value: 'health' } });
  expect(screen.queryByLabelText('本次允许身份与身世剧透')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('知识库'), { target: { value: 'firefly' } });
  expect(screen.getByLabelText('本次允许身份与身世剧透')).not.toBeChecked();
  fireEvent.click(screen.getByLabelText('本次允许身份与身世剧透'));
  const region = await authorize(false);
  expect(region).toHaveTextContent('流萤官方资料 · 允许身份与身世剧透');
  expect(screen.getByLabelText('知识库')).toBeDisabled();
  expect(send).toHaveBeenCalledWith('cloud.prepare', {
    prompt: '今晚怎么跑',
    memoryRefs: [],
    knowledge: { version: 'curated_knowledge_v1', library: 'firefly', allowSpoilers: true },
  });
  fireEvent.click(within(region).getByRole('button', { name: '同意本次上传并发送' }));
  await screen.findByRole('region', { name: '流萤的回答' });
  expect(screen.getByLabelText('本次允许身份与身世剧透')).not.toBeChecked();
});

it('准备响应不能静默扩大剧透权限', async () => {
  mount();
  await screen.findByText('在线发送前会再次确认');
  fireEvent.click(screen.getByText('本次参考'));
  fireEvent.change(screen.getByLabelText('知识库'), { target: { value: 'firefly' } });
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
  await screen.findByText(/知识库选择核对失败/);
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
  expect(await screen.findByRole('region', { name: '知识来源' })).toHaveTextContent(source.url);
  first.unmount();
  mount();
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
