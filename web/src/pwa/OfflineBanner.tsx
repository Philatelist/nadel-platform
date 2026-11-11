import { useT } from '../i18n';
import { useOnline } from './useOnline';

export function OfflineBanner() {
  const t = useT();
  if (useOnline()) return null;
  return (
    <div
      role="status"
      className="border-b border-warning-line bg-warning-soft px-4 py-2 text-sm text-warning-ink"
    >
      {t.pwa.offline}
    </div>
  );
}
