import { createBrowserRouter, RouterProvider } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { RequirePermission } from './components/RequirePermission';
import { HomeRedirect } from './components/HomeRedirect';
import { Layout } from './components/Layout';
import { SignIn } from './components/SignIn';
import { MeProvider } from './me';
import { NotFoundPage } from './pages/NotFoundPage';
import { useT } from './i18n';

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <HomeRedirect /> },
      {
        path: 'plots',
        lazy: () => import('./pages/PlotsPage').then((m) => ({ Component: m.PlotsPage })),
      },
      {
        path: 'plots/:id',
        lazy: () => import('./pages/PlotPage').then((m) => ({ Component: m.PlotPage })),
      },
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
        element: <RequirePermission permission="AUDIT_VIEW" />,
        children: [
          {
            path: 'audit',
            lazy: () => import('./pages/AuditPage').then((m) => ({ Component: m.AuditPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="ROLES_MANAGE" />,
        children: [
          {
            path: 'roles',
            lazy: () => import('./pages/RolesPage').then((m) => ({ Component: m.RolesPage })),
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
    return <div className="grid h-full place-items-center text-ink-muted">{t.auth.signingIn}</div>;
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
