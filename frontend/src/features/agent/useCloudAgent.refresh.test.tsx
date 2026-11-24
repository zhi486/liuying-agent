import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useBridgeStore } from '../../bridge/bridgeStore';
import type { CloudListing } from '../../bridge/cloudAgent';
import { useCloudAgent } from './useCloudAgent';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', async (original) => ({
  ...(await original<typeof import('../../bridge/bridgeStore')>()),
  sendNativeCommand: send,
}));

const commandId = '11111111-1111-4111-8111-111111111111';
const conversationId = '22222222-2222-4222-8222-222222222222';
const nextConversationId = '33333333-3333-4333-8333-333333333333';
const requestId = '44444444-4444-4444-8444-444444444444';
const origin = 'https://agent.example.com';
const prompt = '合成测试问题';
const accepted = (data: unknown) => ({ status: 'accepted', data });
const snapshot = (draftAnswer?: string, answer?: string): CloudListing => ({
  commandId,
  configured: true,
  configuring: false,
  chatAuthorized: true,
  origin,
  conversationId,
  items: [
    {
      requestId,
      prompt,
      busy: !answer,
      attempted: true,
      status: answer ? 'completed' : 'running',
      resultState: answer ? 'available' : 'none',
      reason: null,
      answer: answer ?? null,
      ...(draftAnswer ? { draftAnswer } : {}),
      pendingControl: null,
      confirmedControl: null,
      error: null,
      memoryRefs: [],
    },
  ],
});

function deferred() {
  let resolve!: (value: ReturnType<typeof accepted>) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<ReturnType<typeof accepted>>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

const notify = () =>
  act(() => useBridgeStore.setState((state) => ({ cloudRevision: state.cloudRevision + 1 })));
const mount = () =>
  renderHook(({ status }) => useCloudAgent(status, prompt, []), {
    initialProps: { status: 'ready' },
  });
const settle = async (read: ReturnType<typeof deferred>, listing: CloudListing) => {
  await act(async () => read.resolve(accepted(listing)));
};
let hidden: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.useFakeTimers();
  send.mockReset();
  useBridgeStore.setState({ cloudRevision: 0 });
  hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
});

afterEach(() => {
  vi.useRealTimers();
  hidden.mockRestore();
});

it('读取慢于连续通知时首个有效片段在终态前展示，读取单飞且合并后续通知', async () => {
  const reads = Array.from({ length: 3 }, deferred);
  let count = 0;
  let inFlight = 0;
  let maximum = 0;
  send.mockImplementation((name: string) => {
    expect(name).toBe('cloud.list');
    inFlight += 1;
    maximum = Math.max(maximum, inFlight);
    return reads[count++].promise.finally(() => (inFlight -= 1));
  });
  const hook = mount();
  for (let index = 0; index < 5; index += 1) {
    act(() => vi.advanceTimersByTime(40));
    notify();
  }
  await settle(reads[0], snapshot('第一段'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBe('第一段');
  expect(maximum).toBe(1);
  expect(count).toBe(2);
  for (let index = 0; index < 5; index += 1) notify();
  await settle(reads[1], snapshot('第一段和后续片段'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBe('第一段和后续片段');
  expect(count).toBe(3);
  await settle(reads[2], snapshot(undefined, '最终正式回答'));
  expect(hook.result.current.listing?.items[0].answer).toBe('最终正式回答');
  expect(hook.result.current.listing?.items[0].draftAnswer).toBeUndefined();
  expect(count).toBe(3);
  hook.unmount();
});

it('隐藏时清草稿并丢弃迟到读取，显示后合并为一次新的读取', async () => {
  const initial = deferred();
  const stale = deferred();
  const current = deferred();
  send
    .mockReturnValueOnce(initial.promise)
    .mockReturnValueOnce(stale.promise)
    .mockReturnValueOnce(current.promise);
  const hook = mount();
  await settle(initial, snapshot('隐藏前草稿'));
  notify();
  notify();
  hidden.mockReturnValue(true);
  act(() => document.dispatchEvent(new Event('visibilitychange')));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBeUndefined();
  notify();
  hidden.mockReturnValue(false);
  act(() => document.dispatchEvent(new Event('visibilitychange')));
  expect(send).toHaveBeenCalledTimes(2);
  await settle(stale, snapshot('必须丢弃的旧草稿'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBeUndefined();
  expect(send).toHaveBeenCalledTimes(3);
  await settle(current, snapshot('显示后的新草稿'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBe('显示后的新草稿');
  hook.unmount();
});

it('断开丢弃迟到快照与积压，重连后只继续一次读取', async () => {
  const stale = deferred();
  const current = deferred();
  send.mockReturnValueOnce(stale.promise).mockReturnValueOnce(current.promise);
  const hook = mount();
  notify();
  hook.rerender({ status: 'disconnected' });
  notify();
  await settle(stale, snapshot('断开前的旧草稿'));
  expect(hook.result.current.listing).toBeNull();
  expect(send).toHaveBeenCalledTimes(1);
  hook.rerender({ status: 'ready' });
  await settle(current, snapshot('重连后草稿'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBe('重连后草稿');
  expect(send).toHaveBeenCalledTimes(2);
  hook.unmount();
});

it('卸载后不因积压通知继续读取', async () => {
  const stale = deferred();
  send.mockReturnValue(stale.promise);
  const hook = mount();
  notify();
  hook.unmount();
  await settle(stale, snapshot('卸载前草稿'));
  expect(send).toHaveBeenCalledTimes(1);
});

it('失败清临时草稿并消费已积压的读取，恢复后不会自动重发任务或循环', async () => {
  const initial = deferred();
  const failed = deferred();
  const followup = deferred();
  send
    .mockReturnValueOnce(initial.promise)
    .mockReturnValueOnce(failed.promise)
    .mockReturnValueOnce(followup.promise);
  const hook = mount();
  await settle(initial, snapshot('失败前草稿'));
  notify();
  notify();
  await act(async () => failed.reject(new Error('合成读取失败')));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBeUndefined();
  expect(hook.result.current.error).toContain('不会自动重发');
  expect(send).toHaveBeenCalledTimes(3);
  await settle(followup, snapshot(undefined, '恢复后的正式回答'));
  expect(hook.result.current.listing?.items[0].answer).toBe('恢复后的正式回答');
  expect(send).toHaveBeenCalledTimes(3);
  expect(send.mock.calls.every(([name]) => name === 'cloud.list')).toBe(true);
  hook.unmount();
});

it('无积压的读取失败不会自行继续读取', async () => {
  send.mockRejectedValue(new Error('合成离线'));
  const hook = mount();
  await act(async () => undefined);
  expect(hook.result.current.error).toContain('不会自动重发');
  expect(send).toHaveBeenCalledTimes(1);
  hook.unmount();
});

it('切换会话后旧读取不能覆盖新会话，锁期通知在解锁后跟进', async () => {
  const initial = deferred();
  const stale = deferred();
  const selected = deferred();
  const followup = deferred();
  const next = { ...snapshot(), conversationId: nextConversationId, items: [] };
  send
    .mockReturnValueOnce(initial.promise)
    .mockReturnValueOnce(stale.promise)
    .mockReturnValueOnce(selected.promise)
    .mockReturnValueOnce(followup.promise);
  const hook = mount();
  await settle(initial, snapshot('旧会话草稿'));
  notify();
  let changing!: Promise<boolean>;
  act(() => {
    changing = hook.result.current.changeConversation(nextConversationId);
  });
  notify();
  await settle(selected, next);
  expect(await changing).toBe(true);
  await settle(stale, snapshot('不能覆盖新会话'));
  expect(hook.result.current.listing?.conversationId).toBe(nextConversationId);
  expect(send).toHaveBeenCalledTimes(4);
  await settle(followup, next);
  expect(hook.result.current.listing?.conversationId).toBe(nextConversationId);
  hook.unmount();
});

it.each(['cloud.archiveConversation', 'cloud.deleteConversation'] as const)(
  '%s 完成后迟到快照不得复活草稿',
  async (name) => {
    const initial = deferred();
    const stale = deferred();
    const managed = deferred();
    send
      .mockReturnValueOnce(initial.promise)
      .mockReturnValueOnce(stale.promise)
      .mockReturnValueOnce(managed.promise);
    const hook = mount();
    await settle(initial, snapshot('操作前草稿'));
    notify();
    let managing!: Promise<boolean>;
    act(() => {
      managing = hook.result.current.manageConversation(name, conversationId);
    });
    const next = { ...snapshot(), conversationId: null, items: [] };
    await settle(managed, next);
    expect(await managing).toBe(true);
    await settle(stale, snapshot('不该复活的草稿'));
    expect(hook.result.current.listing?.conversationId).toBeNull();
    expect(hook.result.current.listing?.items).toEqual([]);
    hook.unmount();
  },
);

it('持续授权发送期间的通知在解锁后立即读取，不重复发送且旧快照不回写', async () => {
  const initial = deferred();
  const stale = deferred();
  const sent = deferred();
  const followup = deferred();
  let lists = 0;
  send.mockImplementation((name: string) => {
    if (name === 'cloud.list') return [initial, stale, followup][lists++].promise;
    if (name === 'cloud.prepare')
      return Promise.resolve(
        accepted({
          commandId,
          token: requestId,
          origin,
          prompt,
          memories: [],
          conversation: {
            version: 'conversation_v1',
            id: conversationId,
            approved: true,
            messages: [],
          },
        }),
      );
    if (name === 'cloud.send') return sent.promise;
    throw new Error('禁止其他命令');
  });
  const hook = mount();
  await settle(initial, snapshot());
  notify();
  let preparing!: ReturnType<typeof hook.result.current.prepare>;
  await act(async () => {
    preparing = hook.result.current.prepare();
  });
  notify();
  await settle(stale, snapshot('发送前旧草稿'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBeUndefined();
  await settle(sent, snapshot());
  expect(await preparing).toBe(true);
  expect(lists).toBe(3);
  await settle(followup, snapshot('发送后首段'));
  expect(hook.result.current.listing?.items[0].draftAnswer).toBe('发送后首段');
  expect(send.mock.calls.filter(([name]) => name === 'cloud.send')).toHaveLength(1);
  expect(send.mock.calls.filter(([name]) => name === 'cloud.prepare')).toHaveLength(1);
  hook.unmount();
});
