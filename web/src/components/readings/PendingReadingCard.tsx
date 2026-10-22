import {
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  getListReadingsQueryKey,
  useApproveReading,
  type PendingReading,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { meterLabel, resourceUnit } from '../../meters';
import { problemMessage } from '../../problem';
import { UserContent, formatUser } from '../UserContent';
import { Badge, Button, Notice } from '../ui';
import { RejectReadingDialog } from './RejectReadingDialog';

/** One reading waiting for approval with the interval it would close and its warnings; approve or reject it. */
export function PendingReadingCard({ item }: { item: PendingReading }) {
  const t = useT();
  const { locale } = useLocale();
  const a = t.readings.approvals;
  const queryClient = useQueryClient();
  const { reading, meter, interval, warnings } = item;
  const unit = resourceUnit(t, meter.resource);
  const [rejecting, setRejecting] = useState(false);
  const [error, setError] = useState<string>();

  async function refresh(): Promise<void> {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListReadingsQueryKey({ status: 'PENDING' }) }),
      queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
      queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
    ]);
  }

  const approve = useApproveReading({
    mutation: {
      onSuccess: refresh,
      onError: (e: unknown) => setError(problemMessage(t, e)),
    },
  });

  const title = meter.plot ? (
    formatUser(t.readings.plotNumber, { number: meter.plot.number })
  ) : (
    <UserContent>{meterLabel(meter)}</UserContent>
  );

  return (
    <li className="flex flex-col gap-3 rounded-panel border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Link to={`/meters/${meter.id}`} className="font-semibold text-accent underline">
          {title}
        </Link>
        <span className="text-sm text-ink-muted">
          {t.meters.resource[meter.resource]}, <UserContent>{meter.serial}</UserContent>
        </span>
      </div>
      <p className="font-medium">
        {format(a.submittedValue, {
          value: formatMeterValue(reading.value, locale),
          unit,
          date: formatDay(reading.takenOn, locale),
        })}
      </p>
      <p className="text-sm text-ink-muted">
        {interval
          ? format(a.wouldBe, {
              consumption: formatMeterValue(interval.consumption, locale),
              unit,
              days: interval.days,
              from: formatDay(interval.from, locale),
              to: formatDay(interval.to, locale),
            })
          : a.noInterval}
      </p>
      {warnings.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {warnings.map((w) => (
            <Badge key={w} tone="warning">
              {t.readings.warnings[w]}
            </Badge>
          ))}
        </div>
      )}
      {error && <Notice tone="danger">{error}</Notice>}
      <div className="flex flex-wrap gap-2">
        <Button
          disabled={approve.isPending}
          onClick={() => {
            setError(undefined);
            approve.mutate({ id: reading.id });
          }}
        >
          {a.approve}
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            setError(undefined);
            setRejecting(true);
          }}
        >
          {a.reject}
        </Button>
      </div>
      {rejecting && (
        <RejectReadingDialog
          reading={reading}
          onRejected={async () => {
            setRejecting(false);
            await refresh();
          }}
          onClose={() => setRejecting(false)}
        />
      )}
    </li>
  );
}
