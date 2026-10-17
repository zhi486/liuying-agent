import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import type { AppSnapshot } from '../bridge/contract';
import { AppLayout } from './AppLayout';

const session = vi.hoisted(() => ({ state: 'idle' as AppSnapshot['session']['state'] }));
vi.mock('../bridge/bridgeStore', () => ({
  useBridgeStore: (selector: (value: { snapshot: { session: typeof session } }) => unknown) =>
    selector({ snapshot: { session } }),
}));

describe('跨页面运动入口', () => {
  it.each(['/settings/usage', '/settings/notices'])('%s 返回设置而不是丢失上级', (path) => {
    session.state = 'idle';
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: '返回设置' })).toHaveAttribute('href', '/settings');
  });
  it.each(['/history', '/settings', '/run', '/memories'])('%s 提供明确返回首页入口', (path) => {
    session.state = 'idle';
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: '返回聊天首页' })).toHaveAttribute('href', '/agent');
  });

  it.each(['running', 'paused', 'preparing'] as const)('%s 时可以返回跑步页', (state) => {
    session.state = state;
    render(
      <MemoryRouter initialEntries={['/agent']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: /返回跑步页/ })).toHaveAttribute('href', '/run');
  });

  it.each(['idle', 'finished', 'error'] as const)('%s 时不显示活动跑步', (state) => {
    session.state = state;
    render(
      <MemoryRouter initialEntries={['/agent']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('link', { name: /返回跑步页/ })).not.toBeInTheDocument();
  });

  it('跑步页本身不重复显示返回入口', () => {
    session.state = 'running';
    render(
      <MemoryRouter initialEntries={['/run']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('link', { name: /返回跑步页/ })).not.toBeInTheDocument();
  });

  it('聊天随键盘可视高度调整，退出后还原页面滚动并移除监听', () => {
    const viewport = Object.assign(new EventTarget(), { height: 720, scale: 1, offsetTop: 0 });
    vi.stubGlobal('visualViewport', viewport);
    const previousOverflow = document.body.style.overflow;
    try {
      const mounted = render(
        <MemoryRouter initialEntries={['/agent']}>
          <AppLayout />
        </MemoryRouter>,
      );
      const shell = mounted.container.querySelector<HTMLElement>('.app-shell-agent')!;
      expect(shell.style.getPropertyValue('--chat-viewport-height')).toBe('720px');
      viewport.height = 360;
      act(() => viewport.dispatchEvent(new Event('resize')));
      expect(shell.style.getPropertyValue('--chat-viewport-height')).toBe('360px');
      viewport.offsetTop = 48;
      act(() => viewport.dispatchEvent(new Event('scroll')));
      expect(shell.style.getPropertyValue('--chat-viewport-top')).toBe('48px');
      viewport.scale = 2;
      viewport.height = 180;
      act(() => viewport.dispatchEvent(new Event('resize')));
      expect(shell.style.getPropertyValue('--chat-viewport-height')).toBe('360px');
      expect(document.body.style.overflow).toBe('hidden');
      mounted.unmount();
      expect(document.body.style.overflow).toBe(previousOverflow);
      act(() => viewport.dispatchEvent(new Event('resize')));
      expect(shell.style.getPropertyValue('--chat-viewport-height')).toBe('');
      expect(shell.style.getPropertyValue('--chat-viewport-top')).toBe('');
    } finally {
      vi.unstubAllGlobals();
      document.body.style.overflow = previousOverflow;
    }
  });
});
