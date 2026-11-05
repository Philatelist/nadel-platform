import { useMemo } from 'react';
import { createBrowserRouter, Link, Outlet, RouterProvider } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { LocaleSwitch } from '../components/LocaleSwitch';
import { ThemeSwitch } from '../components/ThemeSwitch';
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
    <div className="flex min-h-full flex-col">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line bg-surface px-4 py-2">
        <Link to="/help" className="font-serif text-xl font-semibold text-ink">
          {t.app.name}
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <LocaleSwitch />
          <ThemeSwitch />
          <Button variant="primary" onClick={signIn}>
            {t.auth.signIn}
          </Button>
        </div>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
        <Outlet />
      </main>
    </div>
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
