import {
  checkPlotReading,
  getListMeterReadingsQueryKey,
  useSubmitPlotReadingWithPhoto,
  type Meter,
  type MeterReading,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue, parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { useDirtyGuard } from '../../pwa/dirtyForms';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { normalizeImage } from '../../lib/normalizeImage';
import { MAX_FILE_BYTES } from '../files/FilePicker';
import { useObjectUrl } from '../files/FilePreview';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';
import { ReadingDateField } from './ReadingDateField';

/** An owner submits a reading for their own plot's meter; it waits for approval. Mount it only while open. */
export function SubmitReadingDialog({
  plotId,
  meter,
  pending,
  onSubmitted,
  onClose,
}: {
  plotId: string;
  meter: Meter;
  /** The newest reading of this meter waiting for approval, when there is one. */
  pending?: MeterReading | undefined;
  onSubmitted: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const queryClient = useQueryClient();
  const [takenOn, setTakenOn] = useState<string>();
  const [input, setInput] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();
  const [photo, setPhoto] = useState<File>();
  const [photoError, setPhotoError] = useState<string>();
  const [processing, setProcessing] = useState(false);
  const [checking, setChecking] = useState(false);
  const [unusual, setUnusual] = useState(false);
  const photoInput = useRef<HTMLInputElement>(null);
  const photoUrl = useObjectUrl(photo);
  useDirtyGuard(input !== '' || photo !== undefined || takenOn !== undefined);

  const parsed = parseMeterValue(input, locale);
  const valueError = submitted && parsed === undefined ? t.meters.valueInvalid : undefined;

  const submitReading = useSubmitPlotReadingWithPhoto({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) });
        onSubmitted();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        // a problem whose field errors the dialog does not show falls back to the general notice
        const shown = Object.keys(fields).some((key) => ['takenOn', 'value', 'photo'].includes(key));
        setNotice(shown ? undefined : problemMessage(t, error));
      },
    },
  });

  function edit(change: () => void) {
    setServerErrors({});
    setNotice(undefined);
    setUnusual(false);
    change();
  }

  async function choosePhoto(list: FileList | null) {
    const chosen = list?.[0];
    if (!chosen) return;
    setPhotoError(undefined);
    setNotice(undefined);
    setProcessing(true);
    try {
      const file = await normalizeImage(chosen);
      if (file === undefined || !['image/jpeg', 'image/png'].includes(file.type)) {
        setPhotoError(t.readings.photo.typeNotAllowed);
      } else if (file.size > MAX_FILE_BYTES) {
        setPhotoError(t.readings.photo.tooLarge);
      } else {
        setPhoto(file);
      }
    } finally {
      setProcessing(false);
      if (photoInput.current) photoInput.current.value = '';
    }
  }

  function send(value: string) {
    setUnusual(false);
    submitReading.mutate({
      id: plotId,
      data: { reading: { meterId: meter.id, takenOn, value }, photo },
    });
  }

  /** Asks the server first (no photo): a rule break shows as a field error, an unusual jump asks for confirmation. */
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (parsed === undefined) return;
    setChecking(true);
    try {
      const check = await checkPlotReading(plotId, { meterId: meter.id, takenOn, value: parsed });
      if (check.problem) {
        const code = check.problem.code;
        const text =
          code in t.readings.errors ? t.readings.errors[code as keyof typeof t.readings.errors] : undefined;
        if (text === undefined) {
          setNotice(t.readings.errors.VALIDATION_FAILED);
        } else if (check.problem.field === 'takenOn') {
          setServerErrors({ takenOn: text });
        } else {
          setServerErrors({ value: text });
        }
        return;
      }
      if (check.warnings.includes('UNUSUAL_CONSUMPTION')) {
        setUnusual(true);
        return;
      }
    } catch {
      // the check itself failed: submit as usual, the server decides
    } finally {
      setChecking(false);
    }
    send(parsed);
  }

  const unit = resourceUnit(t, meter.resource);

  return (
    <Modal title={t.readings.mine.submitTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={(event) => void submit(event)} noValidate>
        <p className="text-[15px] text-ink-muted">{t.readings.mine.submitText}</p>
        <div className="flex flex-col gap-1 text-[15px]">
          <p>
            {format(t.readings.mine.previousReading, {
              value: formatMeterValue(meter.lastReading.value, locale),
              unit,
              date: formatDay(meter.lastReading.takenOn, locale),
              status: t.readings.history.status.APPROVED,
            })}
          </p>
          {pending && (
            <p>
              {format(t.readings.mine.pendingReading, {
                value: formatMeterValue(pending.value, locale),
                unit,
                date: formatDay(pending.takenOn, locale),
              })}
            </p>
          )}
        </div>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <ReadingDateField
          value={takenOn}
          min={meter.installedOn}
          error={serverErrors.takenOn}
          onChange={(value) => edit(() => setTakenOn(value))}
        />
        <TextField
          label={`${t.readings.history.columns.value}, ${unit}`}
          inputMode="decimal"
          value={input}
          hint={t.meters.valueHint}
          error={valueError ?? serverErrors.value}
          onChange={(e) => edit(() => setInput(e.target.value))}
        />
        <div className="flex flex-col gap-2">
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
              alt={t.readings.photo.thumbnailAlt}
              className="size-24 rounded-control border border-line object-cover"
            />
          )}
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" disabled={processing} onClick={() => photoInput.current?.click()}>
              {processing
                ? t.readings.photo.processing
                : photo
                  ? t.readings.photo.replace
                  : t.readings.photo.add}
            </Button>
            {photo && !processing && (
              <Button variant="secondary" onClick={() => setPhoto(undefined)}>
                {t.readings.photo.remove}
              </Button>
            )}
          </div>
          {(photoError ?? serverErrors.photo) && (
            <Notice tone="danger">{photoError ?? serverErrors.photo}</Notice>
          )}
        </div>
        {unusual && (
          <Notice tone="warning">
            <p>{t.readings.warnings.UNUSUAL_CONSUMPTION}</p>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => setUnusual(false)}>
                {t.readings.mine.correctValue}
              </Button>
              <Button onClick={() => parsed !== undefined && send(parsed)}>
                {t.readings.mine.confirmAnyway}
              </Button>
            </div>
          </Notice>
        )}
        <div className="sticky -bottom-5 -mx-5 -mb-5 flex justify-end gap-2 border-t border-line bg-surface p-5">
          <Button variant="secondary" onClick={onClose}>
            {t.meters.cancel}
          </Button>
          <Button type="submit" disabled={submitReading.isPending || processing || checking}>
            {submitReading.isPending ? t.meters.saving : t.readings.mine.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
