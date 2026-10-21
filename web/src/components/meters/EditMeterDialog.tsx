import {
  getGetMeterQueryKey,
  getListMetersQueryKey,
  getListPlotMetersQueryKey,
  useUpdateMeter,
  type Meter,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea, TextField } from '../ui';

/** Edits the serial number, name and note of a meter; mount it only while it should be open. */
export function EditMeterDialog({
  meter,
  onSaved,
  onClose,
}: {
  meter: Meter;
  onSaved: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const m = t.meters;
  const queryClient = useQueryClient();
  const isPlot = meter.kind === 'PLOT';
  const [serial, setSerial] = useState(meter.serial);
  const [name, setName] = useState(meter.name ?? '');
  const [note, setNote] = useState(meter.note ?? '');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const update = useUpdateMeter({
    mutation: {
      onSuccess: async (saved) => {
        queryClient.setQueryData(getGetMeterQueryKey(meter.id), saved);
        await queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() });
        if (meter.plot) {
          await queryClient.invalidateQueries({ queryKey: getListPlotMetersQueryKey(meter.plot.id) });
        }
        onSaved();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      },
    },
  });

  const serialMissing = serial.trim() === '';
  const nameMissing = !isPlot && name.trim() === '';

  function edit(change: () => void) {
    setServerErrors({});
    setNotice(undefined);
    change();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (serialMissing || nameMissing) return;
    update.mutate({
      id: meter.id,
      data: {
        serial: serial.trim(),
        ...(isPlot ? {} : { name: name.trim() }),
        ...(note.trim() ? { note: note.trim() } : {}),
      },
    });
  }

  return (
    <Modal title={m.editTitle} onClose={onClose} size="lg">
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={m.serial}
          value={serial}
          maxLength={50}
          error={(submitted && serialMissing ? t.validation.REQUIRED : undefined) ?? serverErrors.serial}
          onChange={(e) => edit(() => setSerial(e.target.value))}
        />
        {!isPlot && (
          <TextField
            label={m.name}
            value={name}
            maxLength={100}
            error={(submitted && nameMissing ? t.validation.REQUIRED : undefined) ?? serverErrors.name}
            onChange={(e) => edit(() => setName(e.target.value))}
          />
        )}
        <TextArea
          label={m.note}
          value={note}
          maxLength={500}
          rows={3}
          onChange={(e) => edit(() => setNote(e.target.value))}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {m.cancel}
          </Button>
          <Button type="submit" disabled={update.isPending}>
            {update.isPending ? m.saving : m.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
