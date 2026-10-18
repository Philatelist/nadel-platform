import { Panel } from '../components/ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

export function NotFoundPage() {
  const t = useT();
  useDocumentTitle(t.access.notFound);
  return (
    <div>
      <Panel>
        <h1 className="font-serif text-3xl font-semibold text-ink">{t.access.notFound}</h1>
      </Panel>
    </div>
  );
}
