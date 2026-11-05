import { useListChargeTypes, useListTariffs, type TariffSummary } from '@nadel/api-client';
import { format, formatDay, formatRateWithCurrency } from '@nadel/shared';
import { useState } from 'react';
import { Link } from 'react-router';
import { TariffConflicts } from '../components/tariffs/TariffConflicts';
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
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE');
  const tariffs = useListTariffs({ status });
  const l = t.tariffs.list;
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
      <div role="group" aria-label={l.statusLabel} className="flex gap-1 border-b border-line-subtle">
        {(['ACTIVE', 'ARCHIVED'] as const).map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={status === value}
            onClick={() => setStatus(value)}
            className={`-mb-px border-b-2 px-4 py-2 font-medium ${
              status === value ? 'border-accent text-ink' : 'border-transparent text-ink-muted hover:text-ink'
            }`}
          >
            {value === 'ACTIVE' ? l.active : l.archived}
          </button>
        ))}
      </div>
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={t.tariffs.empty}>
        {(types) => (
          <QueryBoundary query={tariffs}>
            {(allTariffs) =>
              status === 'ARCHIVED' && allTariffs.length === 0 ? (
                <p className="text-ink-muted">{l.noArchived}</p>
              ) : (
                <ul className="flex flex-col gap-3">
                  {types.map((type) => {
                    const own = allTariffs.filter((tariff) => tariff.chargeTypeId === type.id);
                    if (status === 'ARCHIVED' && own.length === 0) return null;
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
              )
            }
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
        {format(t.tariffs.validFromShort, { date: formatDay(version.validFrom, locale) })}
        {tariff.validUntil &&
          ` · ${format(t.tariffs.validUntil, { date: formatDay(tariff.validUntil, locale) })}`}{' '}
        · {plotCountText(tariff.plotCount ?? 0)}
      </span>
      <TariffConflicts conflicts={tariff.conflicts} />
    </li>
  );

  function plotCountText(count: number): string {
    const category = new Intl.PluralRules(locale).select(count);
    const forms = t.tariffs.plots;
    const form = category === 'one' ? forms.one : category === 'few' ? forms.few : forms.many;
    return format(form, { count });
  }
}
