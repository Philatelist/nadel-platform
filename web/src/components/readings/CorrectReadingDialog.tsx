import {
  getGetMeterQueryKey,
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  useCorrectReading,
  type Meter,
  type MeterReading,
} from '@nadel/api-client';
import { formatMeterValue, parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { resourceUnit } from '../../meters';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { invalidateAfterReading } from '../charges/chargeInvalidation';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';
import { ReadingDateField } from './ReadingDateField';

/** Corrects the date and value of a counted reading; mount it only while it should be open. */
export function CorrectReadingDialog({
  meter,
  reading,
  onSaved,
  onClose,
}: {
  meter: Meter;
  reading: MeterReading;
  onSaved: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const queryClient = useQueryClient();
  const [takenOn, setTakenOn] = useState(reading.takenOn);
  const [input, setInput] = useState(formatMeterValue(reading.value, locale));
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const parsed = parseMeterValue(input, locale);
  const valueError = submitted && parsed === undefined ? m.valueInvalid : undefined;

  const correct = useCorrectReading({
    mutation: {
      onSuccess: async () => {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getGetMeterQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
          invalidateAfterReading(queryClient),
        ]);
        onSaved();
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
    correct.mutate({ id: reading.id, data: { takenOn, value: parsed } });
  }

  return (
    <Modal title={t.readings.history.correctTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <ReadingDateField
          value={takenOn}
          showHint={false}
          min={meter.installedOn}
          error={serverErrors.takenOn}
          onChange={(value) => edit(() => setTakenOn(value ?? kyivToday()))}
        />
        <TextField
          label={`${t.readings.history.columns.value}, ${resourceUnit(t, meter.resource)}`}
          inputMode="decimal"
          value={input}
          hint={m.valueHint}
          error={valueError ?? serverErrors.value}
          onChange={(e) => edit(() => setInput(e.target.value))}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {m.cancel}
          </Button>
          <Button type="submit" disabled={correct.isPending}>
            {correct.isPending ? m.saving : t.readings.history.correctSubmit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
