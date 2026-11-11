import { useT } from '../i18n';
import { Button, Panel } from '../components/ui';
import { dismissInstallOffer, promptInstall, useInstallOffer } from './installPrompt';

/** The install offer on «My account»: the browser's install prompt, or the steps for Safari on iPhone. */
export function InstallCard() {
  const t = useT();
  const offer = useInstallOffer();
  if (offer === 'hidden') return null;
  return (
    <Panel title={offer === 'ios' ? t.pwa.iosTitle : t.pwa.installTitle}>
      <p>{offer === 'ios' ? t.pwa.iosSteps : t.pwa.installBody}</p>
      <div className="flex flex-wrap gap-2">
        {offer === 'prompt' && <Button onClick={() => void promptInstall()}>{t.pwa.install}</Button>}
        <Button variant="secondary" onClick={dismissInstallOffer}>
          {t.pwa.dismiss}
        </Button>
      </div>
    </Panel>
  );
}
