import { createBrowserRouter, Navigate, RouterProvider } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { Layout } from './components/Layout';
import { SignIn } from './components/SignIn';
import { CabinetPage } from './pages/CabinetPage';
import { useT } from './i18n';

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/map" replace /> },
      // the map is heavy (MapLibre), so it loads as a separate chunk
      { path: 'map', lazy: () => import('./pages/MapPage').then((m) => ({ Component: m.MapPage })) },
      { path: 'cabinet', element: <CabinetPage /> },
    ],
  },
]);

export function App() {
  const auth = useAuth();
  const t = useT();

  if (auth.isLoading) {
    return <div className="grid h-full place-items-center text-muted">{t.auth.signingIn}</div>;
  }
  if (!auth.isAuthenticated) {
    return <SignIn error={auth.error?.message} />;
  }
  return <RouterProvider router={router} />;
}
