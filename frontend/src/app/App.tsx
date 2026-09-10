import { HashRouter } from 'react-router-dom';

import { BridgeProvider } from '../bridge/BridgeProvider';
import { ErrorBoundary } from './ErrorBoundary';
import { AppRoutes } from './routes';

export function App() {
  return (
    <ErrorBoundary>
      <BridgeProvider>
        <HashRouter>
          <AppRoutes />
        </HashRouter>
      </BridgeProvider>
    </ErrorBoundary>
  );
}
