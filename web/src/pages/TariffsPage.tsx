import { useListChargeTypes } from '@nadel/api-client';
import { Link } from 'react-router';
import { chargeTypeName } from '../chargeTypeName';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, buttonClass, PageHeader, Panel } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

export function TariffsPage() {
  const t = useT();
  const canManage = useCan('TARIFFS_MANAGE');
  useDocumentTitle(t.tariffs.title);
  const query = useListChargeTypes();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.tariffs.title}
        actions={
          canManage && (
            <Link to="/charge-types" className={buttonClass('secondary')}>
              {t.tariffs.chargeTypesAction}
            </Link>
          )
        }
      />
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={t.tariffs.empty}>
        {(types) => (
          <ul className="flex flex-col gap-3">
            {types.map((type) => (
              <li key={type.id}>
                <Panel title={chargeTypeName(type, t)}>
                  {!type.active && (
                    <div>
                      <Badge tone="neutral">{t.tariffs.inactive}</Badge>
                    </div>
                  )}
                  <p className="text-ink-muted">{t.tariffs.noTariffs}</p>
                </Panel>
              </li>
            ))}
          </ul>
        )}
      </QueryBoundary>
    </div>
  );
}
