import { HashRouter } from 'react-router-dom';

import { ErrorBoundary } from './ErrorBoundary';
import { AppRoutes } from './routes';

export function App() {
  return (
    <ErrorBoundary>
      <HashRouter>
        <AppRoutes />
      </HashRouter>
    </ErrorBoundary>
  );
}
