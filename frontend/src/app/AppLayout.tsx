import { Link, Outlet, useLocation } from 'react-router-dom';
import { useEffect, useRef } from 'react';
import { BackLink } from '../design-system/BackControl';
import { useButtonRipples } from '../design-system/useButtonRipples';

import { useBridgeStore } from '../bridge/bridgeStore';

import { useAppBackNavigation } from './useAppBackNavigation';
import { usePageTransition } from './usePageTransition';

const pageTitles: Record<string, string> = {
  '/settings': '设置',
  '/memories': '我的记忆',
  '/history': '跑步记录',
  '/history/demo': '模拟跑步记录',
  '/settings/usage': '账单与用量',
  '/settings/chat': '聊天授权',
  '/settings/plugins': 'MCP 插件',
  '/settings/notices': '用户须知',
};

function resolvePageHeader(pathname: string) {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  if (normalized === '/run') return { title: null, compact: true };
  if (pageTitles[normalized]) return { title: pageTitles[normalized], compact: false };
  if (/^\/history\/[^/]+$/.test(normalized)) return { title: '跑后总结', compact: false };
  return null;
}

export function AppLayout() {
  useButtonRipples();
  const sessionState = useBridgeStore((state) => state.snapshot?.session.state);
  const location = useLocation();
  const navigation = useAppBackNavigation();
  const pageHeader = resolvePageHeader(location.pathname);
  const isAgent = location.pathname === '/agent';
  const isRun = location.pathname === '/run';
  const shell = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLElement>(null);
  usePageTransition(content, location.pathname);
  useEffect(() => {
    if (!isAgent && !isRun) return;
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
  }, [isAgent, isRun]);
  const showRunLink =
    location.pathname !== '/run' &&
    (sessionState === 'running' || sessionState === 'paused' || sessionState === 'preparing');
  return (
    <div
      ref={shell}
      className={
        isAgent
          ? 'app-shell app-shell-agent'
          : location.pathname === '/run'
            ? 'app-shell app-shell-run'
            : 'app-shell app-shell-page'
      }
    >
      <button
        type="button"
        className="skip-link"
        onClick={() => document.getElementById('main-content')?.focus()}
      >
        跳到主要内容
      </button>
      {location.pathname !== '/agent' && !isRun && (
        <nav className="app-back-navigation" aria-label="页面返回">
          <BackLink
            to={navigation.target}
            state={navigation.state}
            className="app-back-button"
            label={navigation.label}
            onClick={(event) => {
              if (
                event.button !== 0 ||
                event.ctrlKey ||
                event.metaKey ||
                event.shiftKey ||
                event.altKey
              )
                return;
              event.preventDefault();
              navigation.back();
            }}
          />
        </nav>
      )}
      {location.pathname !== '/agent' && !isRun && (
        <header className={pageHeader?.compact ? 'app-header app-header-compact' : 'app-header'}>
          {pageHeader?.title ? (
            <h1 id="app-page-title" className="app-header-title">
              {pageHeader.title}
            </h1>
          ) : null}
        </header>
      )}
      <main
        ref={content}
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
    </div>
  );
}
