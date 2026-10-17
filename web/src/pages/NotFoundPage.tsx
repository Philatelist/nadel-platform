import { Panel } from '../components/ui';
import { useT } from '../i18n';

export function NotFoundPage() {
  const t = useT();
  return (
    <div>
      <Panel>
        <h1 className="font-serif text-3xl font-semibold text-ink">{t.access.notFound}</h1>
      </Panel>
    </div>
  );
}
