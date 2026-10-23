import {
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  useEnterReadings,
  type Meter,
  type ReadingRowResult,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue, parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { problemMessage } from '../../problem';
import { readingErrorMessage } from '../../readings';
import { ResourceIcon } from '../meters/ResourceIcon';
import { invalidateAfterReading } from '../charges/chargeInvalidation';
import { UserContent } from '../UserContent';
import { Badge, Button, Notice, TextField } from '../ui';
import { ReadingDateField } from './ReadingDateField';

/**
 * One meter of the round as a row of its card: the value, the date of this reading (the round date unless changed here) and its
 * own save. After a save it shows the interval and warnings from the server; nothing is calculated here.
 */
export function RoundRow({
  meter,
  roundDate,
  onSaved,
}: {
  meter: Meter;
  /** The round date when the person changed it; `undefined` while it is still the default (today). */
  roundDate: string | undefined;
  /** Called with the row's value field after a successful save. */
  onSaved: (field: HTMLInputElement | null) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const r = t.readings;
  const queryClient = useQueryClient();
  const unit = resourceUnit(t, meter.resource);
  const valueField = useRef<HTMLInputElement>(null);
  const [input, setInput] = useState('');
  const [rowDate, setRowDate] = useState<string>();
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<ReadingRowResult>();
  const [failure, setFailure] = useState<string>();

  const parsed = parseMeterValue(input, locale);
  const valueError = submitted && parsed === undefined ? t.meters.valueInvalid : undefined;
  const takenOn = rowDate ?? roundDate;

  const enter = useEnterReadings({
    mutation: {
      onSuccess: async (batch) => {
        const row = batch.rows[0];
        if (!row) return;
        setResult(row);
        if (row.outcome !== 'SAVED') return;
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
          invalidateAfterReading(queryClient),
        ]);
        onSaved(valueField.current);
      },
      onError: (error: unknown) => setFailure(problemMessage(t, error)),
    },
  });

  function edit(change: () => void) {
    setResult(undefined);
    setFailure(undefined);
    change();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (parsed === undefined) return;
    setResult(undefined);
    setFailure(undefined);
    enter.mutate({ data: { rows: [{ meterId: meter.id, takenOn, value: parsed }] } });
  }

  const saved = result?.outcome === 'SAVED' ? result : undefined;
  const interval = saved?.reading?.interval;

  return (
    <div className="min-w-0">
      <form className="flex flex-col gap-3" onSubmit={submit} noValidate>
        <div className="flex min-w-0 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 font-semibold break-words">
            <ResourceIcon resource={meter.resource} />
            {meter.plot && (
              <Link to={`/meters/${meter.id}`} className="text-accent underline">
                <UserContent>{meter.serial}</UserContent>
              </Link>
            )}
            {saved && <Badge tone="success">{r.saved}</Badge>}
          </div>
          <div className="text-sm text-ink-muted">
            {format(r.lastReading, {
              value: formatMeterValue(meter.lastReading.value, locale),
              unit,
              date: formatDay(meter.lastReading.takenOn, locale),
            })}
          </div>
        </div>
        <div className="grid grid-cols-1 items-start gap-3 sm:grid-cols-[1fr_1fr_auto]">
          <TextField
            ref={valueField}
            label={format(r.valueLabel, { unit })}
            inputMode="decimal"
            autoComplete="off"
            value={input}
            error={valueError}
            onChange={(e) => edit(() => setInput(e.target.value))}
          />
          <ReadingDateField
            label={r.rowDate}
            value={rowDate ?? roundDate}
            showHint={false}
            min={meter.installedOn}
            onChange={(value) => edit(() => setRowDate(value))}
          />
          <div className="sm:pt-6">
            <Button type="submit" className="w-full sm:w-auto" disabled={enter.isPending || input === ''}>
              {enter.isPending ? r.saving : r.save}
            </Button>
          </div>
        </div>
        {failure && <Notice tone="danger">{failure}</Notice>}
        {result?.outcome === 'REJECTED' && (
          <Notice tone="danger">
            {result.error ? readingErrorMessage(t, locale, meter.resource, result.error) : t.problem.UNKNOWN}
          </Notice>
        )}
        {saved && interval && (
          <p className="text-sm break-words text-ink">
            {format(r.interval, {
              from: formatDay(interval.from, locale),
              to: formatDay(interval.to, locale),
              consumption: formatMeterValue(interval.consumption, locale),
              unit,
              days: interval.days,
            })}
          </p>
        )}
        {saved && saved.supersededReadingIds.length > 0 && <Notice tone="warning">{r.superseded}</Notice>}
        {saved?.warnings.map((warning) => (
          <Notice key={warning} tone="warning">
            {r.warnings[warning]}
          </Notice>
        ))}
      </form>
    </div>
  );
}
