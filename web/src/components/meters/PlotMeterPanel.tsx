import { ApiError, useListPlotMeters, type Meter } from '@nadel/api-client';
import { formatDay, formatMeterValue, format } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { useCan } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, Panel } from '../ui';

function MeterRow({ meter }: { meter: Meter }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const canView = useCan('METERS_VIEW');
  const unit = resourceUnit(t, meter.resource);
  const title = (
    <>
      {m.resource[meter.resource]}, <UserContent>{meter.serial}</UserContent>
    </>
  );
  return (
    <li className="flex flex-col gap-1 py-2">
      <div className="flex flex-wrap items-center gap-2 font-medium">
        {canView ? (
          <Link to={`/meters/${meter.id}`} className="text-accent underline">
            {title}
          </Link>
        ) : (
          title
        )}
        <Badge tone={meter.active ? 'success' : 'neutral'}>{meter.active ? m.active : m.removed}</Badge>
        {meter.replacesMeterId && <Badge tone="info">{m.replacedHint}</Badge>}
      </div>
      <div className="text-sm text-ink-muted">
        {format(m.lastReadingValue, {
          value: formatMeterValue(meter.lastReading.value, locale),
          unit,
          date: formatDay(meter.lastReading.takenOn, locale),
        })}
      </div>
      <div className="text-sm text-ink-muted">
        {meter.removedOn
          ? format(m.periodClosed, {
              from: formatDay(meter.installedOn, locale),
              to: formatDay(meter.removedOn, locale),
            })
          : format(m.periodOpen, { from: formatDay(meter.installedOn, locale) })}
      </div>
    </li>
  );
}

/** The meters of a plot (active first as the server orders them); renders nothing when the server refuses them. */
export function PlotMeterPanel({ plotId }: { plotId: string }) {
  const t = useT();
  const meters = useListPlotMeters(plotId);
  if (meters.error instanceof ApiError && (meters.error.status === 404 || meters.error.status === 403)) {
    return null;
  }
  return (
    <Panel title={t.meters.plotPanelTitle}>
      <QueryBoundary
        query={meters}
        isEmpty={(data) => data.length === 0}
        emptyMessage={t.meters.plotPanelEmpty}
      >
        {(data) => (
          <ul className="divide-y divide-line-subtle">
            {data.map((meter) => (
              <MeterRow key={meter.id} meter={meter} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </Panel>
  );
}
