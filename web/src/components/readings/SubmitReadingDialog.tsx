import { getListMeterReadingsQueryKey, useSubmitPlotReading, type Meter } from '@nadel/api-client';
import { parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';
import { ReadingDateField } from './ReadingDateField';

/** An owner submits a reading for their own plot's meter; it waits for approval. Mount it only while open. */
export function SubmitReadingDialog({
  plotId,
  meter,
  onSubmitted,
  onClose,
}: {
  plotId: string;
  meter: Meter;
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

  const parsed = parseMeterValue(input, locale);
  const valueError = submitted && parsed === undefined ? t.meters.valueInvalid : undefined;

  const submitReading = useSubmitPlotReading({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) });
        onSubmitted();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      },
    },
  });

  function edit(change: () => void) {
    setServerErrors({});
    setNotice(undefined);
    change();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (parsed === undefined) return;
    submitReading.mutate({ id: plotId, data: { meterId: meter.id, takenOn, value: parsed } });
  }

  return (
    <Modal title={t.readings.mine.submitTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{t.readings.mine.submitText}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <ReadingDateField
          value={takenOn}
          min={meter.installedOn}
          error={serverErrors.takenOn}
          onChange={(value) => edit(() => setTakenOn(value))}
        />
        <TextField
          label={`${t.readings.history.columns.value}, ${resourceUnit(t, meter.resource)}`}
          inputMode="decimal"
          value={input}
          hint={t.meters.valueHint}
          error={valueError ?? serverErrors.value}
          onChange={(e) => edit(() => setInput(e.target.value))}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {t.meters.cancel}
          </Button>
          <Button type="submit" disabled={submitReading.isPending}>
            {submitReading.isPending ? t.meters.saving : t.readings.mine.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
