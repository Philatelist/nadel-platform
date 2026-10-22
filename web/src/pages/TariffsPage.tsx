import { useListChargeTypes, useListTariffs, type TariffSummary } from '@nadel/api-client';
import { format, formatDay, formatRateWithCurrency } from '@nadel/shared';
import { Link } from 'react-router';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { UserContent } from '../components/UserContent';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, buttonClass, PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

export function TariffsPage() {
  const t = useT();
  const canManage = useCan('TARIFFS_MANAGE');
  useDocumentTitle(t.tariffs.title);
  const query = useListChargeTypes();
  const tariffs = useListTariffs();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.tariffs.title}
        actions={
          canManage && (
            <>
              <Link to="/charge-types" className={buttonClass('secondary')}>
                {t.tariffs.chargeTypesAction}
              </Link>
              <Link to="/tariffs/new" className={buttonClass('primary')}>
                {t.tariffs.newTariff}
              </Link>
            </>
          )
        }
      />
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={t.tariffs.empty}>
        {(types) => (
          <QueryBoundary query={tariffs}>
            {(allTariffs) => (
              <ul className="flex flex-col gap-3">
                {types.map((type) => {
                  const own = allTariffs.filter((tariff) => tariff.chargeTypeId === type.id);
                  return (
                    <li key={type.id}>
                      <Panel title={<ChargeTypeName type={type} />}>
                        {!type.active && (
                          <div>
                            <Badge tone="neutral">{t.tariffs.inactive}</Badge>
                          </div>
                        )}
                        {own.length === 0 ? (
                          <p className="text-ink-muted">{t.tariffs.noTariffs}</p>
                        ) : (
                          <ul className="flex flex-col">
                            {own.map((tariff) => (
                              <TariffRow key={tariff.id} tariff={tariff} />
                            ))}
                          </ul>
                        )}
                      </Panel>
                    </li>
                  );
                })}
              </ul>
            )}
          </QueryBoundary>
        )}
      </QueryBoundary>
    </div>
  );
}

function TariffRow({ tariff }: { tariff: TariffSummary }) {
  const t = useT();
  const { locale } = useLocale();
  const { version } = tariff;
  return (
    <li className="flex flex-col gap-1 border-b border-line-subtle py-3 first:pt-0 last:border-b-0 last:pb-0">
      <Link to={`/tariffs/${tariff.id}`} className="font-medium text-accent underline">
        <UserContent>{tariff.name}</UserContent>
      </Link>
      <span>
        {formatRateWithCurrency(version.rate, locale)} · {t.tariffs.basis[version.basis]} ·{' '}
        {t.tariffs.frequency[version.frequency]}
      </span>
      <span className="text-sm text-ink-muted">
        {format(t.tariffs.validFromShort, { date: formatDay(version.validFrom, locale) })} ·{' '}
        {plotCountText(tariff.plotCount ?? 0)}
      </span>
    </li>
  );

  function plotCountText(count: number): string {
    const category = new Intl.PluralRules(locale).select(count);
    const forms = t.tariffs.plots;
    const form = category === 'one' ? forms.one : category === 'few' ? forms.few : forms.many;
    return format(form, { count });
  }
}
