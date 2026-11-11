import {
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  useEnterReadings,
  useEnterReadingWithPhoto,
  type Meter,
  type ReadingRowResult,
  type ReadingSaved,
  type RecalculationCause,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue, parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { useCan } from '../../me';
import { kyivToday } from '../../kyivToday';
import { normalizeImage } from '../../lib/normalizeImage';
import { problemFieldErrors, problemMessage } from '../../problem';
import { readingErrorMessage } from '../../readings';
import { MAX_FILE_BYTES } from '../files/FilePicker';
import { useObjectUrl } from '../files/FilePreview';
import { ResourceIcon } from '../meters/ResourceIcon';
import { invalidateAfterReading } from '../charges/chargeInvalidation';
import { UserContent } from '../UserContent';
import { Badge, Button, Notice, TextField } from '../ui';
import { ReadingDateField } from './ReadingDateField';
import { ReadingRecalculationDialog } from './ReadingRecalculationDialog';

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
  const canRecalculate = useCan('RECALCULATION_RUN');
  const [cause, setCause] = useState<RecalculationCause>();
  const [booked, setBooked] = useState(false);
  const [photo, setPhoto] = useState<File>();
  const [photoError, setPhotoError] = useState<string>();
  const [processing, setProcessing] = useState(false);
  const photoInput = useRef<HTMLInputElement>(null);
  const photoUrl = useObjectUrl(photo);

  const parsed = parseMeterValue(input, locale);
  const valueError = submitted && parsed === undefined ? t.meters.valueInvalid : undefined;
  const takenOn = rowDate ?? roundDate;

  async function afterSaved() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
      queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
      invalidateAfterReading(queryClient),
    ]);
    onSaved(valueField.current);
  }

  const enterWithPhoto = useEnterReadingWithPhoto({
    mutation: {
      onSuccess: async (data: ReadingSaved) => {
        setResult({
          index: 0,
          meterId: meter.id,
          outcome: 'SAVED',
          reading: data.reading,
          supersededReadingIds: data.supersededReadingIds,
          warnings: data.warnings,
        });
        setPhoto(undefined);
        await afterSaved();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setFailure(fields.photo ?? fields.value ?? fields.takenOn ?? problemMessage(t, error));
      },
    },
  });

  const enter = useEnterReadings({
    mutation: {
      onSuccess: async (batch) => {
        const row = batch.rows[0];
        if (!row) return;
        if (row.outcome === 'REJECTED' && row.error?.code === 'READING_IN_USE') {
          if (canRecalculate && parsed !== undefined) {
            setCause({
              type: 'READING_ENTER',
              reading: { meterId: meter.id, reading: { takenOn: takenOn ?? kyivToday(), value: parsed } },
            });
            return;
          }
          setResult(row);
          return;
        }
        setResult(row);
        if (row.outcome !== 'SAVED') return;
        await afterSaved();
      },
      onError: (error: unknown) => setFailure(problemMessage(t, error)),
    },
  });

  function edit(change: () => void) {
    setBooked(false);
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
    // a row with a photo goes through the photo operation, every other row through the bulk one
    if (photo)
      enterWithPhoto.mutate({ data: { reading: { meterId: meter.id, takenOn, value: parsed }, photo } });
    else enter.mutate({ data: { rows: [{ meterId: meter.id, takenOn, value: parsed }] } });
  }

  async function choosePhoto(list: FileList | null) {
    const chosen = list?.[0];
    if (!chosen) return;
    setPhotoError(undefined);
    setProcessing(true);
    try {
      const file = await normalizeImage(chosen);
      if (file === undefined || !['image/jpeg', 'image/png'].includes(file.type)) {
        setPhotoError(r.photo.typeNotAllowed);
      } else if (file.size > MAX_FILE_BYTES) {
        setPhotoError(r.photo.tooLarge);
      } else {
        edit(() => setPhoto(file));
      }
    } finally {
      setProcessing(false);
      if (photoInput.current) photoInput.current.value = '';
    }
  }

  const pending = enter.isPending || enterWithPhoto.isPending;
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
            <Button
              type="submit"
              className="w-full sm:w-auto"
              disabled={pending || processing || input === ''}
            >
              {pending ? r.saving : r.save}
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <input
            ref={photoInput}
            type="file"
            hidden
            data-testid="photo-input"
            accept="image/*"
            capture="environment"
            onChange={(e) => void choosePhoto(e.target.files)}
          />
          {photo && photoUrl !== undefined && (
            <img
              src={photoUrl}
              alt={r.photo.thumbnailAlt}
              className="size-12 rounded-control border border-line object-cover"
            />
          )}
          <Button
            variant="secondary"
            className="min-h-11"
            disabled={processing}
            onClick={() => photoInput.current?.click()}
          >
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              className="mr-2 size-5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
              <circle cx="12" cy="13" r="3.5" />
            </svg>
            {processing ? r.photo.processing : photo ? r.photo.replace : r.photo.addRow}
          </Button>
          {photo && !processing && (
            <Button variant="secondary" className="min-h-11" onClick={() => edit(() => setPhoto(undefined))}>
              {r.photo.remove}
            </Button>
          )}
        </div>
        {photoError && <Notice tone="danger">{photoError}</Notice>}
        {failure && <Notice tone="danger">{failure}</Notice>}
        {booked && <Notice tone="success">{r.recalc.entered}</Notice>}
        {result?.outcome === 'REJECTED' && (
          <Notice tone="danger">
            {result.error?.code === 'READING_IN_USE'
              ? r.usedNeedsChairman
              : result.error
                ? readingErrorMessage(t, locale, meter.resource, result.error)
                : t.problem.UNKNOWN}
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
      {cause && (
        <ReadingRecalculationDialog
          meterId={meter.id}
          cause={cause}
          onDone={(recalculated) => {
            setCause(undefined);
            setBooked(recalculated);
            setInput('');
            onSaved(valueField.current);
          }}
          onClose={() => setCause(undefined)}
        />
      )}
    </div>
  );
}
