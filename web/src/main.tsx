import { ApiError, configureApi } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { User } from 'oidc-client-ts';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { App } from './App';
import { OIDC_AUTHORITY, OIDC_CLIENT_ID, oidcConfig } from './auth';
import { I18nProvider } from './i18n';
import './index.css';

function currentAccessToken(): string | null {
  const key = `oidc.user:${OIDC_AUTHORITY}:${OIDC_CLIENT_ID}`;
  const stored = window.sessionStorage.getItem(key);
  return stored ? User.fromStorageString(stored).access_token : null;
}

configureApi({ getToken: currentAccessToken });

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) =>
        // client errors (4xx) will not fix themselves on retry
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <QueryClientProvider client={queryClient}>
        <I18nProvider>
          <App />
        </I18nProvider>
      </QueryClientProvider>
    </AuthProvider>
  </StrictMode>,
);
