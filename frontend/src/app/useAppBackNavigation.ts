import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import { useLocation, useNavigate, useNavigationType } from 'react-router-dom';

function fixedParentPath(path: string, state: BackNavigationState) {
  if (
    path === '/settings/notices' &&
    (state?.from === '/settings/chat' ||
      state?.from === '/settings/usage' ||
      state?.from === '/memories')
  )
    return state.from;
  if (
    state?.from === '/agent' &&
    state.restore === 'management' &&
    [
      '/settings/usage',
      '/settings/chat',
      '/settings/plugins',
      '/memories',
      '/settings/notices',
    ].includes(path)
  )
    return '/agent';
  if (path.startsWith('/settings/')) return '/settings';
  if (path.startsWith('/history/')) return '/history';
  if (path === '/memories' && state?.from === '/settings') return '/settings';
  return null;
}

type BackNavigationState = {
  from?: unknown;
  restore?: unknown;
} | null;

type ReturnNavigationState = { restore: 'management' } | null;

const internalPathPattern = /^\/(?!\/)[A-Za-z0-9/_-]*$/;

function suppliedSource(state: BackNavigationState) {
  if (typeof state?.from !== 'string') return null;
  if (!internalPathPattern.test(state.from)) return null;
  if (state.from === '/agent' || state.from === '/run') return state.from;
  return state.from.startsWith('/settings') ||
    state.from.startsWith('/history') ||
    state.from === '/memories'
    ? state.from
    : null;
}

function backLabel(path: string, target: string) {
  if (target === '/settings') return '返回设置';
  if (target === '/history') return '返回跑步记录';
  if (target === '/run') return '返回跑步';
  if (target === '/agent') return path === '/agent' ? '返回' : '返回聊天';
  if (target === '/memories') return '返回长期记忆';
  return '返回上一级';
}

function returnState(state: BackNavigationState): ReturnNavigationState {
  return state?.restore === 'management' ? { restore: 'management' } : null;
}

/** 只回退本次应用内已访问的页面；直接进入子页时替换为默认上级。 */
export function useAppBackNavigation() {
  const location = useLocation();
  const navigate = useNavigate();
  const action = useNavigationType();
  const [history, setHistory] = useState({ entries: [location], index: 0 });
  useLayoutEffect(() => {
    setHistory((old) => {
      if (old.entries[old.index]?.key === location.key) return old;
      const index = old.entries.findIndex((entry) => entry.key === location.key);
      if (action === 'POP')
        return index >= 0 ? { ...old, index } : { entries: [location], index: 0 };
      if (action === 'REPLACE') {
        const entries = [...old.entries];
        entries[old.index] = location;
        return { entries, index: old.index };
      }
      return { entries: [...old.entries.slice(0, old.index + 1), location], index: old.index + 1 };
    });
  }, [location, action]);
  const previous =
    history.entries[history.index]?.key === location.key
      ? history.entries[history.index - 1]
      : undefined;
  const state = location.state as BackNavigationState;
  const explicitSource = suppliedSource(state);
  const fixedParent = fixedParentPath(location.pathname, state);
  // 管理抽屉的五个直达入口回到原抽屉；其他页面仍按默认层级返回。
  const target =
    fixedParent ??
    explicitSource ??
    (previous && previous.pathname !== location.pathname ? previous.pathname : null) ??
    '/agent';
  const targetState = target === '/agent' ? returnState(state) : null;
  const label = targetState ? '返回设置与管理' : backLabel(location.pathname, target);
  const back = useCallback(() => {
    // 需要恢复抽屉时用 replace 注入一次性状态；普通入口仍保留真实历史回退。
    if (previous?.pathname === target && !targetState) void navigate(-1);
    else void navigate(target, { replace: true, state: targetState });
  }, [navigate, previous, target, targetState]);

  useEffect(() => {
    const onBack = (event: Event) => {
      // 原生返回先交给当前弹层的取消流程，不越过待确认操作或修改权限。
      const modal = document.querySelector<HTMLDialogElement>('dialog[open]');
      if (modal) {
        event.preventDefault();
        if (modal.dispatchEvent(new Event('cancel', { cancelable: true }))) modal.close();
        return;
      }
      if (document.querySelector('[role="dialog"][aria-modal="true"][aria-hidden="false"]')) {
        event.preventDefault();
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        return;
      }
      // 聊天是根页面；不因历史中还留有设置页而重新绕回去。
      if (location.pathname !== '/agent') {
        event.preventDefault();
        back();
      }
    };
    window.addEventListener('liuying:navigate-back', onBack);
    return () => window.removeEventListener('liuying:navigate-back', onBack);
  }, [back, location.pathname]);

  return { target, state: targetState, label, back };
}
