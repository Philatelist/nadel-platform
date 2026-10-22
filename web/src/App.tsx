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
        element: <RequirePermission permission={['PLOTS_VIEW', 'PLOTS_EDIT']} />,
        children: [
          {
            path: 'plots/new',
            lazy: () => import('./pages/PlotFormPage').then((m) => ({ Component: m.PlotFormPage })),
          },
          {
            path: 'plots/:id/edit',
            lazy: () => import('./pages/PlotFormPage').then((m) => ({ Component: m.PlotFormPage })),
          },
        ],
      },
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
        element: <RequirePermission permission={['TARIFFS_VIEW', 'TARIFFS_MANAGE']} />,
        children: [
          {
            path: 'tariffs/new',
            lazy: () => import('./pages/TariffFormPage').then((m) => ({ Component: m.TariffFormPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="TARIFFS_VIEW" />,
        children: [
          {
            path: 'tariffs/:id',
            lazy: () => import('./pages/TariffPage').then((m) => ({ Component: m.TariffPage })),
          },
          {
            path: 'tariffs',
            lazy: () => import('./pages/TariffsPage').then((m) => ({ Component: m.TariffsPage })),
          },
          {
            path: 'charge-types',
            lazy: () => import('./pages/ChargeTypesPage').then((m) => ({ Component: m.ChargeTypesPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="METERS_VIEW" />,
        children: [
          {
            path: 'meters',
            lazy: () => import('./pages/MetersPage').then((m) => ({ Component: m.MetersPage })),
          },
          {
            path: 'meters/:id',
            lazy: () => import('./pages/MeterPage').then((m) => ({ Component: m.MeterPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="READINGS_ENTER" />,
        children: [
          {
            path: 'readings',
            lazy: () => import('./pages/ReadingsRoundPage').then((m) => ({ Component: m.ReadingsRoundPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="READINGS_APPROVE" />,
        children: [
          {
            path: 'readings/approvals',
            lazy: () => import('./pages/ApprovalsPage').then((m) => ({ Component: m.ApprovalsPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="IMPORT_RUN" />,
        children: [
          {
            path: 'imports',
            lazy: () => import('./pages/ImportPage').then((m) => ({ Component: m.ImportPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission="CHARGES_VIEW" />,
        children: [
          {
            path: 'charges',
            lazy: () => import('./pages/ChargesPage').then((m) => ({ Component: m.ChargesPage })),
          },
          {
            path: 'charges/months/:ym',
            lazy: () => import('./pages/ChargesMonthPage').then((m) => ({ Component: m.ChargesMonthPage })),
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
        element: <RequirePermission permission={['SETTINGS_MANAGE', 'METERS_MANAGE']} any />,
        children: [
          {
            path: 'settings',
            lazy: () => import('./pages/SettingsPage').then((m) => ({ Component: m.SettingsPage })),
          },
        ],
      },
      {
        element: <RequirePermission permission={['METERS_VIEW', 'METERS_MANAGE']} />,
        children: [
          {
            path: 'settings/meter-tree',
            lazy: () => import('./pages/MeterTreePage').then((m) => ({ Component: m.MeterTreePage })),
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
    return <SignIn hasError={auth.error !== undefined} />;
  }
  return (
    <MeProvider>
      <RouterProvider router={router} />
    </MeProvider>
  );
}
