import type { TariffVersion } from '@nadel/api-client';
import { format, formatDay, formatRateWithCurrency } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';

/** The versions of a tariff as the server sends them (newest first). */
export function VersionHistory({ versions }: { versions: readonly TariffVersion[] }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.tariffs.card;
  return (
    <ol className="flex flex-col">
      {versions.map((version) => (
        <li
          key={version.id}
          className="flex flex-col gap-1 border-b border-line-subtle py-3 first:pt-0 last:border-b-0 last:pb-0"
        >
          <span className="font-medium">
            {version.validTo
              ? format(c.range, {
                  from: formatDay(version.validFrom, locale),
                  to: formatDay(version.validTo, locale),
                })
              : format(c.rangeOpen, { from: formatDay(version.validFrom, locale) })}
          </span>
          <span>
            {formatRateWithCurrency(version.rate, locale)} · {t.tariffs.basis[version.basis]} ·{' '}
            {t.tariffs.frequency[version.frequency]}
          </span>
          {version.dueDate && (
            <span className="text-sm text-ink-muted">
              {format(c.dueOn, { date: formatDay(version.dueDate, locale) })}
            </span>
          )}
          {version.note && (
            <span className="text-sm text-ink-muted">
              <UserContent>{version.note}</UserContent>
            </span>
          )}
        </li>
      ))}
    </ol>
  );
}
