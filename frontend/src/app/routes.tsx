import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { RunningPage } from '../features/running/RunningPage';
import { AgentPage } from '../features/agent/AgentPage';
import { AppLayout } from './AppLayout';

const HistoryPage = lazy(() =>
  import('../features/history/HistoryPage').then((module) => ({
    default: module.HistoryPage,
  })),
);
const SettingsPage = lazy(() =>
  import('../features/settings/SettingsPage').then((module) => ({
    default: module.SettingsPage,
  })),
);

const RunDetailPage = lazy(() =>
  import('../features/history/RunDetailPage').then((module) => ({ default: module.RunDetailPage })),
);

const MemoriesPage = lazy(() =>
  import('../features/memory/MemoriesPage').then((module) => ({ default: module.MemoriesPage })),
);

function RouteFallback() {
  return (
    <section className="route-skeleton" aria-label="页面加载中">
      <span />
      <span />
      <span />
    </section>
  );
}

export function AppRoutes() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/agent" replace />} />
          <Route path="/agent" element={<AgentPage />} />
          <Route path="/memories" element={<MemoriesPage />} />
          <Route path="/run" element={<RunningPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/history/:sessionId" element={<RunDetailPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/agent" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
