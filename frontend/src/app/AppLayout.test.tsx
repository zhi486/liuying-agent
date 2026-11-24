import { act, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import type { AppSnapshot } from '../bridge/contract';
import { AppLayout } from './AppLayout';

const session = vi.hoisted(() => ({ state: 'idle' as AppSnapshot['session']['state'] }));
vi.mock('../bridge/bridgeStore', () => ({
  useBridgeStore: (selector: (value: { snapshot: { session: typeof session } }) => unknown) =>
    selector({ snapshot: { session } }),
}));

describe('跨页面运动入口', () => {
  it.each([
    '/settings/usage',
    '/settings/chat',
    '/settings/plugins',
    '/memories',
    '/settings/notices',
  ])('%s 从管理抽屉进入后返回原抽屉，系统返回一致', async (path) => {
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/agent'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate(path, { state: { from: '/agent', restore: 'management' } }));
    expect(screen.getByRole('link', { name: '返回设置与管理' })).toHaveAttribute('href', '/agent');
    act(() => {
      window.dispatchEvent(new Event('liuying:navigate-back', { cancelable: true }));
    });
    expect(router.state.location.pathname).toBe('/agent');
    expect(router.state.location.state).toEqual({ restore: 'management' });
  });
  it.each([
    '/settings/usage',
    '/settings/chat',
    '/settings/plugins',
    '/memories',
    '/settings/notices',
  ])('%s 从设置进入后返回设置', async (path) => {
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/settings'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate(path, { state: { from: '/settings' } }));
    fireEvent.click(screen.getByRole('link', { name: '返回设置' }));
    expect(router.state.location.pathname).toBe('/settings');
  });
  it.each([
    ['/settings', '设置'],
    ['/memories', '我的记忆'],
    ['/history', '跑步记录'],
    ['/history/demo', '模拟跑步记录'],
    ['/history/run-1', '跑后总结'],
    ['/settings/usage', '账单与用量'],
    ['/settings/chat', '聊天授权'],
    ['/settings/plugins', 'MCP 插件'],
    ['/settings/notices', '用户须知'],
  ])('%s 由应用外壳提供唯一页面标题', (path, title) => {
    session.state = 'idle';
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppLayout />
      </MemoryRouter>,
    );
    const headings = screen.getAllByRole('heading', { level: 1 });
    expect(headings).toHaveLength(1);
    expect(headings[0]).toHaveTextContent(title);
  });

  it('跑步页由页面内容提供标题，应用外壳不重复生成', () => {
    session.state = 'idle';
    render(
      <MemoryRouter initialEntries={['/run']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  it.each(['/settings/chat', '/settings/usage', '/settings/notices', '/settings/plugins'])(
    '%s 直接打开时返回默认设置上级',
    (path) => {
      session.state = 'idle';
      render(
        <MemoryRouter initialEntries={[path]}>
          <AppLayout />
        </MemoryRouter>,
      );
      expect(screen.getByRole('link', { name: '返回设置' })).toHaveAttribute('href', '/settings');
    },
  );
  it.each(['/history', '/settings', '/memories'])('%s 提供明确返回首页入口', (path) => {
    session.state = 'idle';
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: '返回聊天' })).toHaveAttribute('href', '/agent');
  });

  it.each([
    '/settings/chat',
    '/settings/usage',
    '/settings/notices',
    '/settings/plugins',
    '/memories',
    '/history',
    '/settings',
  ])('从聊天进入 %s 后按页面层级返回', async (path) => {
    session.state = 'idle';
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/agent'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate(path));
    fireEvent.click(
      screen.getByRole('link', { name: path.startsWith('/settings/') ? '返回设置' : '返回聊天' }),
    );
    if (path.startsWith('/settings/')) {
      expect(router.state.location.pathname).toBe('/settings');
      fireEvent.click(screen.getByRole('link', { name: '返回聊天' }));
    }
    expect(router.state.location.pathname).toBe('/agent');
  });

  it('从设置进入长期记忆时返回设置，而不是跨级回到聊天', async () => {
    session.state = 'idle';
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/settings'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate('/memories', { state: { from: '/settings' } }));
    expect(screen.getByRole('link', { name: '返回设置' })).toHaveAttribute('href', '/settings');
    fireEvent.click(screen.getByRole('link', { name: '返回设置' }));
    expect(router.state.location.pathname).toBe('/settings');
  });

  it('设置内进入子页返回设置，再返回实际入口；替换同一页面不多退一次', async () => {
    session.state = 'idle';
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/agent'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate('/settings'));
    await act(() => router.navigate('/settings/chat'));
    await act(() => router.navigate('/settings/chat', { replace: true, state: null }));
    fireEvent.click(screen.getByRole('link', { name: '返回设置' }));
    expect(router.state.location.pathname).toBe('/settings');
    fireEvent.click(screen.getByRole('link', { name: '返回聊天' }));
    expect(router.state.location.pathname).toBe('/agent');
  });

  it('从跑步进入设置后逐级返回到跑步，而不是跳到聊天', async () => {
    session.state = 'idle';
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/run'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate('/settings'));
    expect(screen.getByRole('link', { name: '返回跑步' })).toHaveAttribute('href', '/run');
    await act(() => router.navigate('/settings/notices'));
    fireEvent.click(screen.getByRole('link', { name: '返回设置' }));
    expect(router.state.location.pathname).toBe('/settings');
    expect(screen.getByRole('link', { name: '返回跑步' })).toHaveAttribute('href', '/run');
    fireEvent.click(screen.getByRole('link', { name: '返回跑步' }));
    expect(router.state.location.pathname).toBe('/run');
  });

  it.each(['/history/demo', '/history/run-1'])(
    '%s 直接进入也只有一个返回入口且回记录列表',
    (path) => {
      render(
        <MemoryRouter initialEntries={[path]}>
          <AppLayout />
        </MemoryRouter>,
      );
      expect(screen.getAllByRole('link', { name: '返回跑步记录' })).toHaveLength(1);
      expect(screen.getByRole('link', { name: '返回跑步记录' })).toHaveAttribute(
        'href',
        '/history',
      );
    },
  );

  it('直接进入子页使用替换兜底，不在系统返回时反复绕回子页', async () => {
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/settings/chat'],
    });
    render(<RouterProvider router={router} />);
    fireEvent.click(screen.getByRole('link', { name: '返回设置' }));
    expect(router.state.location.pathname).toBe('/settings');
    fireEvent.click(screen.getByRole('link', { name: '返回聊天' }));
    expect(router.state.location.pathname).toBe('/agent');
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/agent');
  });

  it('系统返回与页面按钮共用回退，聊天根页交回原生处理', async () => {
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/agent'],
    });
    const mounted = render(<RouterProvider router={router} />);
    await act(() => router.navigate('/settings/chat'));
    const event = new Event('liuying:navigate-back', { cancelable: true });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
    expect(router.state.location.pathname).toBe('/settings');
    const settingsRoot = new Event('liuying:navigate-back', { cancelable: true });
    act(() => {
      window.dispatchEvent(settingsRoot);
    });
    expect(settingsRoot.defaultPrevented).toBe(true);
    expect(router.state.location.pathname).toBe('/agent');
    const root = new Event('liuying:navigate-back', { cancelable: true });
    window.dispatchEvent(root);
    expect(root.defaultPrevented).toBe(false);
    mounted.unmount();
    const disposed = new Event('liuying:navigate-back', { cancelable: true });
    window.dispatchEvent(disposed);
    expect(disposed.defaultPrevented).toBe(false);
  });

  it('系统返回优先触发弹窗取消，不越过不可取消的操作', async () => {
    const router = createMemoryRouter([{ path: '*', element: <AppLayout /> }], {
      initialEntries: ['/agent'],
    });
    render(<RouterProvider router={router} />);
    await act(() => router.navigate('/settings'));
    const modal = document.createElement('dialog');
    modal.setAttribute('open', '');
    const cancel = vi.fn((event: Event) => event.preventDefault());
    modal.addEventListener('cancel', cancel);
    document.body.append(modal);
    try {
      const event = new Event('liuying:navigate-back', { cancelable: true });
      act(() => {
        window.dispatchEvent(event);
      });
      expect(cancel).toHaveBeenCalledTimes(1);
      expect(modal.open).toBe(true);
      expect(event.defaultPrevented).toBe(true);
      expect(router.state.location.pathname).toBe('/settings');
    } finally {
      modal.remove();
    }
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
