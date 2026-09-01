import { Outlet } from 'react-router-dom';

import { AppNavigation } from './AppNavigation';

export function AppLayout() {
  return (
    <div className="app-shell">
      <div className="ambient-light" aria-hidden="true" />
      <header className="app-header">
        <span className="wordmark">流萤</span>
        <span className="companion-copy">陪你稳稳跑完</span>
      </header>
      <main className="app-content">
        <Outlet />
      </main>
      <AppNavigation />
    </div>
  );
}
