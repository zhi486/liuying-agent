import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { RunningPage } from '../features/running/RunningPage';
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
          <Route index element={<Navigate to="/run" replace />} />
          <Route path="/run" element={<RunningPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/run" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
