import {
  useListMeterReadings,
  useListPlotMeters,
  type Meter,
  type MeterReading,
  type OwnedPlot,
  type ReadingStatus,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue } from '@nadel/shared';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { useMe } from '../../me';
import { meterLabel, resourceUnit } from '../../meters';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent, formatUser } from '../UserContent';
import { Badge, Button, Notice, Panel, type BadgeTone } from '../ui';
import { SubmitReadingDialog } from './SubmitReadingDialog';
import { ResourceIcon } from '../meters/ResourceIcon';

const STATUS_TONE: Readonly<Record<ReadingStatus, BadgeTone>> = {
  APPROVED: 'success',
  PENDING: 'info',
  REJECTED: 'warning',
  SUPERSEDED: 'neutral',
};

function ReadingLine({ reading, meter }: { reading: MeterReading; meter: Meter }) {
  const t = useT();
  const { locale } = useLocale();
  const h = t.readings.history;
  const unit = resourceUnit(t, meter.resource);
  return (
    <li className="flex flex-col gap-1 py-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-medium">
          {formatDay(reading.takenOn, locale)}: {formatMeterValue(reading.value, locale)} {unit}
        </span>
        <Badge tone={STATUS_TONE[reading.status]}>{h.status[reading.status]}</Badge>
      </div>
      {reading.rejectReason && (
        <span className="break-words text-sm text-ink-muted">
          {formatUser(h.rejectReason, { reason: reading.rejectReason })}
        </span>
      )}
      {reading.interval && (
        <span className="text-sm text-ink-muted">
          {format(h.interval, {
            consumption: formatMeterValue(reading.interval.consumption, locale),
            unit,
            days: reading.interval.days,
            from: formatDay(reading.interval.from, locale),
            to: formatDay(reading.interval.to, locale),
          })}
        </span>
      )}
    </li>
  );
}

function MyMeterCard({ plotId, meter }: { plotId: string; meter: Meter }) {
  const t = useT();
  const { locale } = useLocale();
  const history = useListMeterReadings(meter.id);
  const [dialog, setDialog] = useState(false);
  const [done, setDone] = useState(false);
  const unit = resourceUnit(t, meter.resource);
  return (
    <li className="flex flex-col gap-3 rounded-control border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="font-semibold">
          <ResourceIcon resource={meter.resource} className="mr-1.5" />
          {t.meters.resource[meter.resource]}, <UserContent>{meterLabel(meter)}</UserContent>
        </h4>
        <Badge tone={meter.active ? 'success' : 'neutral'}>
          {meter.active ? t.meters.active : t.meters.removed}
        </Badge>
      </div>
      <p className="text-sm text-ink-muted">
        {format(t.meters.lastReadingValue, {
          value: formatMeterValue(meter.lastReading.value, locale),
          unit,
          date: formatDay(meter.lastReading.takenOn, locale),
        })}
      </p>
      {done && <Notice tone="success">{t.readings.mine.submitted}</Notice>}
      {meter.active && (
        <div>
          <Button
            onClick={() => {
              setDone(false);
              setDialog(true);
            }}
          >
            {t.readings.mine.submit}
          </Button>
        </div>
      )}
      <QueryBoundary
        query={history}
        isEmpty={(data) => data.readings.length === 0}
        emptyMessage={t.readings.history.empty}
      >
        {(data) => (
          <ul className="divide-y divide-line-subtle">
            {data.readings.map((r) => (
              <ReadingLine key={r.id} reading={r} meter={meter} />
            ))}
          </ul>
        )}
      </QueryBoundary>
      {dialog && (
        <SubmitReadingDialog
          plotId={plotId}
          meter={meter}
          onSubmitted={() => {
            setDialog(false);
            setDone(true);
          }}
          onClose={() => setDialog(false)}
        />
      )}
    </li>
  );
}

function MyPlotMeters({ plot }: { plot: OwnedPlot }) {
  const t = useT();
  const meters = useListPlotMeters(plot.plotId);
  return (
    <Panel title={formatUser(t.readings.mine.meterOfPlot, { number: plot.number })}>
      <QueryBoundary
        query={meters}
        isEmpty={(data) => data.length === 0}
        emptyMessage={t.readings.mine.empty}
      >
        {(data) => (
          <ul className="flex flex-col gap-3">
            {data.map((meter) => (
              <MyMeterCard key={meter.id} plotId={plot.plotId} meter={meter} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </Panel>
  );
}

/** "My meters" on the account page: the meters of the member's own plots, their readings and a way to submit one. */
export function MyMetersPanel() {
  const t = useT();
  const { ownedPlots } = useMe();
  if (ownedPlots.length === 0) return null;
  return (
    <section aria-labelledby="my-meters" className="flex min-w-0 flex-col gap-3">
      <h2 id="my-meters" className="font-serif text-lg font-semibold text-ink">
        {t.readings.mine.title}
      </h2>
      {ownedPlots.map((p) => (
        <MyPlotMeters key={p.plotId} plot={p} />
      ))}
    </section>
  );
}
