import { useAuth } from 'react-oidc-context';
import { useT } from '../i18n';

export function SignIn({ error }: { error?: string | undefined }) {
  const auth = useAuth();
  const t = useT();
  return (
    <main className="grid h-full place-items-center px-4">
      <div className="flex w-full max-w-sm flex-col gap-5 rounded-2xl border border-line bg-surface p-8">
        <h1 className="font-display text-3xl font-bold">{t.app.name}</h1>
        <p className="text-muted">{t.auth.signInHint}</p>
        {error && <p className="text-sm text-status-red">{error}</p>}
        <button
          type="button"
          onClick={() => void auth.signinRedirect()}
          className="rounded-lg bg-accent px-4 py-2.5 font-semibold text-on-accent hover:opacity-90"
        >
          {t.auth.signIn}
        </button>
      </div>
    </main>
  );
}
