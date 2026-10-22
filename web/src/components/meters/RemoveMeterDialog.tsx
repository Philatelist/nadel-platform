import {
  getGetMeterQueryKey,
  getListMetersQueryKey,
  getListPlotMetersQueryKey,
  useRemoveMeter,
  type Meter,
} from '@nadel/api-client';
import { parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { resourceUnit } from '../../meters';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';

/** Takes a meter out of service on a date with its final reading; mount it only while it should be open. */
export function RemoveMeterDialog({
  meter,
  onRemoved,
  onClose,
}: {
  meter: Meter;
  onRemoved: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const queryClient = useQueryClient();
  const today = kyivToday();
  const [removedOn, setRemovedOn] = useState(today);
  const [finalReading, setFinalReading] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const parsed = parseMeterValue(finalReading, locale);
  const dateError = removedOn === '' ? m.dateRequired : undefined;
  const valueError = parsed === undefined ? m.valueInvalid : undefined;

  const remove = useRemoveMeter({
    mutation: {
      onSuccess: async (saved) => {
        queryClient.setQueryData(getGetMeterQueryKey(meter.id), saved);
        await queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() });
        if (meter.plot) {
          await queryClient.invalidateQueries({ queryKey: getListPlotMetersQueryKey(meter.plot.id) });
        }
        onRemoved();
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
    if (dateError || parsed === undefined) return;
    remove.mutate({ id: meter.id, data: { removedOn, finalReading: parsed } });
  }

  return (
    <Modal title={m.removeTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{m.removeText}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={m.removedOn}
          type="date"
          value={removedOn}
          max={today}
          min={meter.installedOn}
          error={(submitted ? dateError : undefined) ?? serverErrors.removedOn}
          onChange={(e) => edit(() => setRemovedOn(e.target.value))}
        />
        <TextField
          label={`${m.finalReading}, ${resourceUnit(t, meter.resource)}`}
          inputMode="decimal"
          value={finalReading}
          hint={m.valueHint}
          error={(submitted ? valueError : undefined) ?? serverErrors.finalReading}
          onChange={(e) => edit(() => setFinalReading(e.target.value))}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {m.cancel}
          </Button>
          <Button type="submit" disabled={remove.isPending}>
            {remove.isPending ? m.saving : m.removeSubmit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
