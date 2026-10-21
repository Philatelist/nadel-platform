import { useAuth } from 'react-oidc-context';
import { useLocale, useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';
import { Button, Notice, Panel } from './ui';

export function SignIn({ hasError }: { hasError: boolean }) {
  const auth = useAuth();
  const t = useT();
  const { locale } = useLocale();
  useDocumentTitle();
  return (
    <main className="grid h-full place-items-center px-4">
      <div className="w-full max-w-[480px]">
        <Panel>
          <h1 className="font-serif text-3xl font-semibold text-ink">{t.app.name}</h1>
          <p className="text-ink-muted">{t.auth.signInHint}</p>
          {hasError && <Notice tone="danger">{t.auth.signInFailed}</Notice>}
          <Button
            variant="primary"
            onClick={() => void auth.signinRedirect({ extraQueryParams: { ui_locales: locale } })}
          >
            {t.auth.signIn}
          </Button>
        </Panel>
      </div>
    </main>
  );
}
