import { Link } from 'react-router';
import { useT } from '../../i18n';
import { originState, useHelpOrigin } from '../../help/helpOrigin';
import { isStandalone } from '../../pwa/installPrompt';

/**
 * «How to install on your phone»: a link to the install help, so the steps stay findable after the install card was
 * hidden. Not shown when the app already runs installed (standalone display mode, or `navigator.standalone` on iOS).
 */
export function HowToInstallLink() {
  const t = useT();
  const origin = useHelpOrigin();
  if (isStandalone()) return null;
  return (
    <Link
      to="/help/member-install"
      state={originState(origin)}
      className="inline-flex min-h-11 items-center text-[15px] text-accent underline"
    >
      {t.pwa.howToInstall}
    </Link>
  );
}
