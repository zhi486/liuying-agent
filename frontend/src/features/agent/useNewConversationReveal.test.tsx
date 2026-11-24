import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { revealCircle, useNewConversationReveal } from './useNewConversationReveal';

const media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
const animate = vi.fn();
const cancel = vi.fn();
const skip = vi.fn();
const start = vi.fn();
let button: HTMLButtonElement;

beforeEach(() => {
  vi.useFakeTimers();
  media.matches = false;
  vi.stubGlobal('matchMedia', () => media);
  button = document.createElement('button');
  button.innerHTML = '<svg></svg>';
  document.body.append(button);
  vi.spyOn(button.firstElementChild!, 'getBoundingClientRect').mockReturnValue({
    left: 260,
    top: 20,
    width: 20,
    height: 20,
  } as DOMRect);
  cancel.mockReset();
  skip.mockReset();
  animate.mockReset().mockReturnValue({ finished: Promise.resolve(), cancel });
  Object.defineProperty(document.documentElement, 'animate', {
    configurable: true,
    value: animate,
  });
  start.mockReset().mockImplementation((update: () => Promise<void>) => {
    let finish!: () => void;
    const finished = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const ready = Promise.resolve().then(update);
    skip.mockImplementation(finish);
    return { ready, finished, skipTransition: skip };
  });
  Object.defineProperty(document, 'startViewTransition', { configurable: true, value: start });
  document.documentElement.style.setProperty('--motion-chat-reveal', '.48s');
  document.documentElement.style.setProperty('--motion-chat-capture-limit', '600ms');
});

afterEach(() => {
  button.remove();
  delete document.documentElement.dataset.newChatReveal;
  delete document.documentElement.dataset.motion;
  delete (document as Partial<Document>).startViewTransition;
  delete (document.documentElement as Partial<HTMLElement>).animate;
  document.documentElement.removeAttribute('style');
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each([
  [320, 800],
  [393, 800],
  [1024, 800],
  [393, 420],
])('圆覆盖 %s×%s 的全部角落', (width, height) => {
  const circle = revealCircle({ left: 260, top: 20, width: 20, height: 20 }, width, height);
  expect(circle.x).toBe(270);
  expect(circle.y).toBe(30);
  for (const x of [0, width])
    for (const y of [0, height])
      expect(Math.hypot(x - circle.x, y - circle.y)).toBeLessThan(circle.radius);
});

it('从加号中心揭示真实新画面，只操作新快照，构建秒单位按毫秒处理', async () => {
  const change = vi.fn().mockResolvedValue(true);
  const { result } = renderHook(useNewConversationReveal);
  await act(async () => {
    await result.current(button, change);
  });
  expect(change).toHaveBeenCalledOnce();
  expect(start).toHaveBeenCalledOnce();
  expect(animate.mock.calls[0][0][0]).toEqual({ clipPath: 'circle(0px at 270px 30px)' });
  expect(animate.mock.calls[0][1]).toMatchObject({
    duration: 480,
    pseudoElement: '::view-transition-new(root)',
  });
  expect(document.documentElement.dataset.newChatReveal).toBeUndefined();
  expect(cancel).toHaveBeenCalledOnce();
});

it.each(['off', 'reduced', 'system', 'unsupported'])(
  '%s 正常新建，不保存快照或播放圆形',
  async (policy) => {
    if (policy === 'system') media.matches = true;
    else if (policy === 'unsupported')
      Object.defineProperty(document, 'startViewTransition', { value: undefined });
    else document.documentElement.dataset.motion = policy;
    const change = vi.fn().mockResolvedValue(true);
    const { result } = renderHook(useNewConversationReveal);
    await act(async () => {
      await result.current(button, change);
    });
    expect(change).toHaveBeenCalledOnce();
    expect(start).not.toHaveBeenCalled();
    expect(animate).not.toHaveBeenCalled();
  },
);

it('原生拒绝不揭示，API 同步失败不重复新建', async () => {
  const { result } = renderHook(useNewConversationReveal);
  const denied = vi.fn().mockResolvedValue(false);
  await act(async () => {
    await result.current(button, denied);
  });
  expect(animate).not.toHaveBeenCalled();
  expect(denied).toHaveBeenCalledOnce();
  start.mockImplementation(() => {
    throw new Error('不支持');
  });
  const accepted = vi.fn().mockResolvedValue(true);
  await act(async () => {
    await result.current(button, accepted);
  });
  expect(accepted).toHaveBeenCalledOnce();
  expect(document.documentElement.dataset.newChatReveal).toBeUndefined();
});

it.each(['timeout', 'resize', 'policy', 'unmount'])(
  '%s 立即跳过视觉效果，不重发业务操作且防连续点击',
  async (reason) => {
    let resolve!: (value: boolean) => void;
    const change = vi.fn(
      () =>
        new Promise<boolean>((done) => {
          resolve = done;
        }),
    );
    const { result, unmount } = renderHook(useNewConversationReveal);
    let pending!: Promise<void>;
    await act(async () => {
      pending = result.current(button, change);
    });
    await act(async () => {
      await result.current(button, change);
    });
    expect(change).toHaveBeenCalledOnce();
    await act(async () => {
      if (reason === 'timeout') vi.advanceTimersByTime(601);
      else if (reason === 'resize') window.dispatchEvent(new Event('resize'));
      else if (reason === 'policy') document.documentElement.dataset.motion = 'off';
      else unmount();
    });
    expect(skip).toHaveBeenCalled();
    expect(document.documentElement.dataset.newChatReveal).toBeUndefined();
    await act(async () => {
      resolve(true);
      await pending;
    });
    expect(animate).not.toHaveBeenCalled();
    expect(change).toHaveBeenCalledOnce();
    expect(document.documentElement.dataset.newChatReveal).toBeUndefined();
  },
);
