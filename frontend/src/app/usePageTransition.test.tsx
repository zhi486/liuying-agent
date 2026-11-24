import { act, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { pageTransition, usePageTransition } from './usePageTransition';

const animate = vi.fn();
const media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
const animations: { cancel: ReturnType<typeof vi.fn> }[] = [];

function Scene({ path }: { path: string }) {
  const ref = useRef<HTMLElement>(null);
  usePageTransition(ref, path);
  return <main ref={ref}>{path}</main>;
}

beforeEach(() => {
  animations.length = 0;
  animate.mockReset().mockImplementation(() => {
    const animation = { cancel: vi.fn() };
    animations.push(animation);
    return animation;
  });
  Object.defineProperty(HTMLElement.prototype, 'animate', { configurable: true, value: animate });
  media.matches = false;
  vi.stubGlobal('matchMedia', () => media);
  for (const [name, value] of Object.entries({ forward: 240, back: 200, peer: 180, reduced: 100 }))
    document.documentElement.style.setProperty(`--motion-page-${name}`, `${value}ms`);
});

afterEach(() => {
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
  delete document.documentElement.dataset.motion;
  for (const name of ['forward', 'back', 'peer', 'reduced'])
    document.documentElement.style.removeProperty(`--motion-page-${name}`);
  vi.unstubAllGlobals();
});

it.each([
  ['/settings', '/settings/notices', 'forward'],
  ['/settings/chat', '/settings', 'back'],
  ['/history/run-1', '/history', 'back'],
  ['/settings', '/memories', 'forward'],
  ['/memories', '/settings', 'back'],
  ['/settings/chat', '/settings/plugins', 'peer'],
  ['/agent', '/run', 'peer'],
  ['/settings', '/agent', 'peer'],
  ['/agent', '/agent', null],
])('%s → %s 使用 %s，不改变导航目标', (from, to, direction) => {
  expect(pageTransition(from, to)).toBe(direction);
});

it('初载与同页更新不播放；进出方向相反，快速导航与卸载取消旧动画', () => {
  const { rerender, unmount } = render(<Scene path="/settings" />);
  expect(animate).not.toHaveBeenCalled();
  rerender(<Scene path="/settings/chat" />);
  expect(animate.mock.calls[0][0][0].transform).toBe('translateX(24px)');
  expect(animate.mock.calls[0][1].duration).toBe(240);
  rerender(<Scene path="/settings" />);
  expect(animations[0].cancel).toHaveBeenCalledOnce();
  expect(animate.mock.calls[1][0][0].transform).toBe('translateX(-24px)');
  expect(animate.mock.calls[1][1].duration).toBe(200);
  rerender(<Scene path="/settings" />);
  expect(animate).toHaveBeenCalledTimes(2);
  unmount();
  expect(animations[1].cancel).toHaveBeenCalledOnce();
});

it.each(['reduced', 'system'])('%s 仅短淡入，关闭效果完全不播放', (policy) => {
  if (policy === 'system') media.matches = true;
  else document.documentElement.dataset.motion = policy;
  const { rerender } = render(<Scene path="/settings" />);
  rerender(<Scene path="/settings/chat" />);
  expect(animate.mock.calls[0][0]).toEqual([{ opacity: 0 }, { opacity: 1 }]);
  expect(animate.mock.calls[0][1].duration).toBe(100);
  document.documentElement.dataset.motion = 'off';
  rerender(<Scene path="/settings" />);
  expect(animate).toHaveBeenCalledOnce();
});

it('播放途中切换动态效果策略会取消，固定视口页面从不做位移', async () => {
  const { rerender } = render(<Scene path="/settings" />);
  rerender(<Scene path="/agent" />);
  expect(animate.mock.calls[0][0]).toEqual([{ opacity: 0 }, { opacity: 1 }]);
  await act(async () => {
    document.documentElement.dataset.motion = 'reduced';
    await Promise.resolve();
  });
  expect(animations[0].cancel).toHaveBeenCalledOnce();
});

it('构建压缩后的秒单位仍按毫秒传给 WAAPI', () => {
  document.documentElement.style.setProperty('--motion-page-forward', '.24s');
  const { rerender } = render(<Scene path="/settings" />);
  rerender(<Scene path="/settings/chat" />);
  expect(animate.mock.calls[0][1].duration).toBe(240);
});
