import { ApiError, useGetTariff } from '@nadel/api-client';
import { Link, useParams } from 'react-router';
import { chargeTypeName } from '../chargeTypeName';
import { QueryBoundary } from '../components/QueryBoundary';
import { ScopeEditor } from '../components/tariffs/ScopeEditor';
import { NewVersionForm } from '../components/tariffs/NewVersionForm';
import { VersionHistory } from '../components/tariffs/VersionHistory';
import { Badge, PageHeader, Panel } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';
import { NotFoundPage } from './NotFoundPage';

/** `/tariffs/:id`: the tariff, its versions (newest first) and, with TARIFFS_MANAGE, a form for a new version. */
export function TariffPage() {
  const { id = '' } = useParams();
  const t = useT();
  const canManage = useCan('TARIFFS_MANAGE');
  const query = useGetTariff(id);
  useDocumentTitle(query.data?.name ?? t.tariffs.title);
  if (query.error instanceof ApiError && query.error.status === 404) return <NotFoundPage />;
  const c = t.tariffs.card;
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/tariffs" className="text-ink-muted hover:text-ink">
        {t.tariffs.back}
      </Link>
      <QueryBoundary query={query}>
        {(tariff) => (
          <>
            <PageHeader
              title={tariff.name}
              description={`${c.chargeType}: ${chargeTypeName(tariff.chargeType, t)}`}
              badge={!tariff.chargeType.active && <Badge tone="neutral">{t.tariffs.inactive}</Badge>}
            />
            <ScopeEditor tariff={tariff} />
            <Panel title={c.history}>
              <VersionHistory versions={tariff.versions} />
            </Panel>
            {canManage && (
              <Panel title={c.newVersion}>
                <NewVersionForm tariff={tariff} />
              </Panel>
            )}
          </>
        )}
      </QueryBoundary>
    </div>
  );
}
