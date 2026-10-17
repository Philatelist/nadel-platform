import { useAuth } from 'react-oidc-context';
import { useT } from '../i18n';
import { Button, Notice, Panel } from './ui';

export function SignIn({ error }: { error?: string | undefined }) {
  const auth = useAuth();
  const t = useT();
  return (
    <main className="grid h-full place-items-center px-4">
      <div className="w-full max-w-[480px]">
        <Panel>
          <h1 className="font-serif text-3xl font-semibold text-ink">{t.app.name}</h1>
          <p className="text-ink-muted">{t.auth.signInHint}</p>
          {error && <Notice tone="danger">{error}</Notice>}
          <Button variant="primary" onClick={() => void auth.signinRedirect()}>
            {t.auth.signIn}
          </Button>
        </Panel>
      </div>
    </main>
  );
}
