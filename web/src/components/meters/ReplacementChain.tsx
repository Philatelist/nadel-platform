import type { Meter } from '@nadel/api-client';
import { formatDay, formatMeterValue, format } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { formatUser } from '../UserContent';

/**
 * The replacement chain of a meter as server facts: the meter that replaced it (with this meter's own removal day and
 * final reading) and the meter it replaced. The serial is a link inside the translated sentence; `link` switches the
 * links off for a viewer who may not open meters.
 */
export function ReplacementChain({ meter, link = true }: { meter: Meter; link?: boolean }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const unit = resourceUnit(t, meter.resource);
  const value = (v: string): string => `${formatMeterValue(v, locale)} ${unit}`;
  const serialOf = (id: string, serial: string) =>
    link ? (
      <Link to={`/meters/${id}`} className="text-accent underline">
        {formatUser('{serial}', { serial })}
      </Link>
    ) : (
      serial
    );
  const { replacedBy, replaces } = meter;
  if (!replacedBy && !replaces) return null;
  return (
    <>
      {replacedBy && meter.removedOn && meter.finalReading && (
        <p data-testid="replaced-by">
          {formatUser(
            format(m.chainReplacedBy, {
              date: formatDay(meter.removedOn, locale),
              value: value(meter.finalReading),
            }),
            { serial: serialOf(replacedBy.id, replacedBy.serial) },
          )}
        </p>
      )}
      {replaces && (
        <p data-testid="replaces">
          {formatUser(
            format(m.chainReplaced, {
              date: formatDay(replaces.removedOn, locale),
              value: value(replaces.finalReading),
            }),
            { serial: serialOf(replaces.id, replaces.serial) },
          )}
        </p>
      )}
    </>
  );
}
