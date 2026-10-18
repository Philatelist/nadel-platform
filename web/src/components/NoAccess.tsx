import { Panel } from './ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

export function NoAccess() {
  const t = useT();
  useDocumentTitle(t.access.denied);
  return (
    <div role="alert">
      <Panel>
        <h1 className="font-serif text-3xl font-semibold text-ink">{t.access.denied}</h1>
      </Panel>
    </div>
  );
}
