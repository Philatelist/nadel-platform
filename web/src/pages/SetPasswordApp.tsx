import { PublicShell } from '../components/PublicShell';
import { useT } from '../i18n';
import { SetPasswordPage } from './SetPasswordPage';

/** The set-password page outside the sign-in gate: no router, no `MeProvider`, only public API calls. */
export function SetPasswordApp() {
  const t = useT();
  return (
    <PublicShell
      brand={<span className="font-serif text-xl font-semibold text-ink">{t.app.name}</span>}
      maxWidth="max-w-xl"
    >
      <SetPasswordPage />
    </PublicShell>
  );
}
