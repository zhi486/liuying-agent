import { Link, Outlet, useLocation } from 'react-router-dom';
import { useEffect, useRef } from 'react';

import { useBridgeStore } from '../bridge/bridgeStore';

import { AppNavigation } from './AppNavigation';

export function AppLayout() {
  const sessionState = useBridgeStore((state) => state.snapshot?.session.state);
  const location = useLocation();
  const isAgent = location.pathname === '/agent';
  const shell = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!isAgent) return;
    const element = shell.current;
    const viewport = window.visualViewport;
    const previousOverflow = document.body.style.overflow;
    // 键盘缩小可视区域时只调整聊天容器，不滚动整页寻找输入框。
    const resize = () => {
      if (viewport && viewport.scale !== 1) return;
      element?.style.setProperty(
        '--chat-viewport-height',
        `${viewport?.height ?? window.innerHeight}px`,
      );
      element?.style.setProperty('--chat-viewport-top', `${viewport?.offsetTop ?? 0}px`);
    };
    document.body.style.overflow = 'hidden';
    resize();
    window.addEventListener('resize', resize);
    viewport?.addEventListener('resize', resize);
    viewport?.addEventListener('scroll', resize);
    return () => {
      document.body.style.overflow = previousOverflow;
      element?.style.removeProperty('--chat-viewport-height');
      element?.style.removeProperty('--chat-viewport-top');
      window.removeEventListener('resize', resize);
      viewport?.removeEventListener('resize', resize);
      viewport?.removeEventListener('scroll', resize);
    };
  }, [isAgent]);
  const showRunLink =
    location.pathname !== '/run' &&
    (sessionState === 'running' || sessionState === 'paused' || sessionState === 'preparing');
  return (
    <div ref={shell} className={isAgent ? 'app-shell app-shell-agent' : 'app-shell'}>
      <button
        type="button"
        className="skip-link"
        onClick={() => document.getElementById('main-content')?.focus()}
      >
        跳到主要内容
      </button>
      {location.pathname !== '/agent' && (
        <header className="app-header">
          {location.pathname.startsWith('/settings/') ? (
            <Link to="/settings" className="quiet-button" aria-label="返回设置">
              ‹ 返回
            </Link>
          ) : ['/history', '/settings', '/run', '/memories'].includes(location.pathname) ? (
            <Link to="/agent" className="quiet-button" aria-label="返回聊天首页">
              ‹ 返回
            </Link>
          ) : (
            <span className="wordmark">流萤</span>
          )}
        </header>
      )}
      <main
        id="main-content"
        className={location.pathname === '/agent' ? 'app-content app-content-agent' : 'app-content'}
        tabIndex={-1}
      >
        {showRunLink && (
          <Link className="active-run-link" to="/run">
            {sessionState === 'paused'
              ? '跑步已暂停'
              : sessionState === 'preparing'
                ? '正在准备跑步'
                : '跑步进行中'}
            ，返回跑步页
          </Link>
        )}
        <Outlet />
      </main>
      <AppNavigation />
    </div>
  );
}
