import { createBrowserRouter, Navigate, RouterProvider } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { RequirePermission } from './components/RequirePermission';
import { Layout } from './components/Layout';
import { SignIn } from './components/SignIn';
import { MeProvider } from './me';
import { NotFoundPage } from './pages/NotFoundPage';
import { useT } from './i18n';

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/account" replace /> },
      {
        path: 'account',
        lazy: () => import('./pages/AccountPage').then((m) => ({ Component: m.AccountPage })),
      },
      {
        element: <RequirePermission permission="MEMBERS_VIEW" />,
        children: [
          {
            path: 'members',
            lazy: () => import('./pages/MembersPage').then((m) => ({ Component: m.MembersPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="MEMBERS_EDIT" />,
        children: [
          {
            path: 'members/new',
            lazy: () => import('./pages/MemberFormPage').then((m) => ({ Component: m.MemberFormPage })),
          },
        ],
      },
      {
        path: 'members/:id',
        lazy: () => import('./pages/MemberPage').then((m) => ({ Component: m.MemberPage })),
      },
      { path: '*', element: <NotFoundPage /> },
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
  return (
    <MeProvider>
      <RouterProvider router={router} />
    </MeProvider>
  );
}
