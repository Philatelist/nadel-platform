import { useMemo } from 'react';
import { createBrowserRouter, Link, Outlet, RouterProvider } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { PublicShell } from '../components/PublicShell';
import { Button } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { NotFoundPage } from '../pages/NotFoundPage';
import { HelpIndexPage } from './HelpIndexPage';
import { HelpPageView } from './HelpPageView';
import { HelpViewerProvider } from './helpViewer';
import { ANONYMOUS_VIEWER } from './helpVisibility';

function PublicHelpLayout() {
  const auth = useAuth();
  const t = useT();
  const { locale } = useLocale();
  const signIn = (): void => {
    const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    void auth.signinRedirect({ state: { returnTo }, extraQueryParams: { ui_locales: locale } });
  };
  return (
    <PublicShell
      brand={
        <Link to="/help" className="font-serif text-xl font-semibold text-ink">
          {t.app.name}
        </Link>
      }
      action={
        <Button variant="primary" onClick={signIn}>
          {t.auth.signIn}
        </Button>
      }
    >
      <Outlet />
    </PublicShell>
  );
}

/** The help for a visitor who is not signed in: its own router, the anonymous viewer, no `MeProvider`, no API calls. */
export function PublicHelpApp() {
  const router = useMemo(
    () =>
      createBrowserRouter([
        {
          element: <PublicHelpLayout />,
          children: [
            { path: 'help', element: <HelpIndexPage /> },
            { path: 'help/:id', element: <HelpPageView /> },
            { path: '*', element: <NotFoundPage /> },
          ],
        },
      ]),
    [],
  );
  return (
    <HelpViewerProvider viewer={ANONYMOUS_VIEWER}>
      <RouterProvider router={router} />
    </HelpViewerProvider>
  );
}
