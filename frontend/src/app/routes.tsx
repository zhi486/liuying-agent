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
const AgentUsagePage = lazy(() =>
  import('../features/settings/AgentUsagePage').then((module) => ({
    default: module.AgentUsagePage,
  })),
);
const AgentNoticesPage = lazy(() =>
  import('../features/settings/AgentNoticesPage').then((module) => ({
    default: module.AgentNoticesPage,
  })),
);
const AgentChatConsentPage = lazy(() =>
  import('../features/settings/AgentChatConsentPage').then((module) => ({
    default: module.AgentChatConsentPage,
  })),
);
const McpSettingsPage = lazy(() =>
  import('../features/settings/McpSettingsPage').then((module) => ({
    default: module.McpSettingsPage,
  })),
);
const ComponentReferenceLabPage = lazy(() =>
  import('../features/design-lab/ComponentReferenceLabPage').then((module) => ({
    default: module.ComponentReferenceLabPage,
  })),
);

const RunDetailPage = lazy(() =>
  import('../features/history/RunDetailPage').then((module) => ({ default: module.RunDetailPage })),
);
const SimulatedRunPage = lazy(() =>
  import('../features/history/SimulatedRunPage').then((module) => ({
    default: module.SimulatedRunPage,
  })),
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
        <Route path="/design/reference" element={<ComponentReferenceLabPage />} />
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/agent" replace />} />
          <Route path="/agent" element={<AgentPage />} />
          <Route path="/memories" element={<MemoriesPage />} />
          <Route path="/run" element={<RunningPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/history/demo" element={<SimulatedRunPage />} />
          <Route path="/history/:sessionId" element={<RunDetailPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/settings/usage" element={<AgentUsagePage />} />
          <Route path="/settings/chat" element={<AgentChatConsentPage />} />
          <Route path="/settings/plugins" element={<McpSettingsPage />} />
          <Route path="/settings/notices" element={<AgentNoticesPage />} />
          <Route path="*" element={<Navigate to="/agent" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
