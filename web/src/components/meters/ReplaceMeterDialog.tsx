import {
  getGetMeterQueryKey,
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  getListPlotMetersQueryKey,
  useReplaceMeter,
  type Meter,
} from '@nadel/api-client';
import { parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { resourceUnit } from '../../meters';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';

/**
 * Replaces a meter in one action: the old one is removed on the date with its final reading, the new one is
 * installed the same day. A counter that wrapped around is a replacement by itself (same serial, initial reading 0),
 * offered as a shortcut. Mount it only while it should be open.
 */
export function ReplaceMeterDialog({
  meter,
  onReplaced,
  onClose,
}: {
  meter: Meter;
  /** Called with the new meter after the server created it. */
  onReplaced: (created: Meter) => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const queryClient = useQueryClient();
  const today = kyivToday();
  const [replacedOn, setReplacedOn] = useState(today);
  const [finalReading, setFinalReading] = useState('');
  const [serial, setSerial] = useState('');
  const [initialReading, setInitialReading] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();
  const unit = resourceUnit(t, meter.resource);

  const parsedFinal = parseMeterValue(finalReading, locale);
  const parsedInitial = parseMeterValue(initialReading, locale);
  const dateError = replacedOn === '' ? m.dateRequired : undefined;
  const serialError = serial.trim() === '' ? m.serialRequired : undefined;

  const replace = useReplaceMeter({
    mutation: {
      onSuccess: async (created) => {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getGetMeterQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
          meter.plot
            ? queryClient.invalidateQueries({ queryKey: getListPlotMetersQueryKey(meter.plot.id) })
            : Promise.resolve(),
        ]);
        onReplaced(created);
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

  function fillWrapAround() {
    edit(() => {
      setSerial(meter.serial);
      setInitialReading('0');
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (dateError || serialError || parsedFinal === undefined || parsedInitial === undefined) return;
    replace.mutate({
      id: meter.id,
      data: { replacedOn, finalReading: parsedFinal, serial: serial.trim(), initialReading: parsedInitial },
    });
  }

  return (
    <Modal title={m.replaceTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{m.replaceText}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={m.replacedOn}
          type="date"
          value={replacedOn}
          max={today}
          min={meter.installedOn}
          error={(submitted ? dateError : undefined) ?? serverErrors.replacedOn}
          onChange={(e) => edit(() => setReplacedOn(e.target.value))}
        />
        <TextField
          label={`${m.oldFinalReading}, ${unit}`}
          inputMode="decimal"
          value={finalReading}
          hint={m.valueHint}
          error={
            (submitted && parsedFinal === undefined ? m.valueInvalid : undefined) ?? serverErrors.finalReading
          }
          onChange={(e) => edit(() => setFinalReading(e.target.value))}
        />
        <TextField
          label={m.newSerial}
          value={serial}
          maxLength={100}
          error={(submitted ? serialError : undefined) ?? serverErrors.serial}
          onChange={(e) => edit(() => setSerial(e.target.value))}
        />
        <TextField
          label={`${m.newInitialReading}, ${unit}`}
          inputMode="decimal"
          value={initialReading}
          hint={m.valueHint}
          error={
            (submitted && parsedInitial === undefined ? m.valueInvalid : undefined) ??
            serverErrors.initialReading
          }
          onChange={(e) => edit(() => setInitialReading(e.target.value))}
        />
        <div className="flex flex-col items-start gap-2 rounded-control border border-line-subtle bg-accent-soft p-3">
          <p className="text-sm text-ink-muted">{m.wrapAroundHint}</p>
          <Button variant="secondary" onClick={fillWrapAround}>
            {m.wrapAroundAction}
          </Button>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {m.cancel}
          </Button>
          <Button type="submit" disabled={replace.isPending}>
            {replace.isPending ? m.saving : m.replaceSubmit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
