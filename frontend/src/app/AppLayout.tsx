import { Link, Outlet, useLocation } from 'react-router-dom';

import { useBridgeStore } from '../bridge/bridgeStore';

import { AppNavigation } from './AppNavigation';

export function AppLayout() {
  const sessionState = useBridgeStore((state) => state.snapshot?.session.state);
  const location = useLocation();
  const showRunLink =
    location.pathname !== '/run' &&
    (sessionState === 'running' || sessionState === 'paused' || sessionState === 'preparing');
  return (
    <div className="app-shell">
      <button
        type="button"
        className="skip-link"
        onClick={() => document.getElementById('main-content')?.focus()}
      >
        跳到主要内容
      </button>
      {location.pathname !== '/agent' && (
        <header className="app-header">
          <span className="wordmark">流萤</span>
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
