import { useLocation } from 'react-router';
import { useT } from '../i18n';
import { Button } from '../components/ui';
import { useAnyDirtyForm } from './dirtyForms';
import { applyUpdate, hasOidcCallback, useUpdateAvailable } from './registerPwa';

/** «A new version is available»: applied only on the button, never by itself, and not while a form is dirty. */
export function UpdateBar() {
  const t = useT();
  const available = useUpdateAvailable();
  const isDirty = useAnyDirtyForm();
  const { search } = useLocation();
  if (!available) return null;
  const signingIn = hasOidcCallback(search);
  const blocked = isDirty || signingIn;
  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line bg-info-soft px-4 py-2 text-sm text-info-ink"
    >
      <span className="font-medium">{t.pwa.updateAvailable}</span>
      {blocked && (
        <span id="update-blocked-hint">
          {signingIn ? t.pwa.updateBlockedSignIn : t.pwa.updateBlockedDirty}
        </span>
      )}
      <Button
        variant="secondary"
        className="ml-auto"
        disabled={blocked}
        aria-describedby={blocked ? 'update-blocked-hint' : undefined}
        onClick={() => void applyUpdate(isDirty)}
      >
        {t.pwa.update}
      </Button>
    </div>
  );
}
