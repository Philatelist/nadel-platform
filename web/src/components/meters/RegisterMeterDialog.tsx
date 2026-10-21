import {
  MeterKind,
  MeterResource,
  getListMetersQueryKey,
  getListPlotMetersQueryKey,
  useRegisterMeter,
  type Meter,
  type MeterKind as MeterKindValue,
  type MeterResource as MeterResourceValue,
} from '@nadel/api-client';
import { parseMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { PlotPicker, type PickedPlot } from '../tariffs/PlotPicker';
import { UserContent } from '../UserContent';
import { Button, Notice, Select, TextArea, TextField } from '../ui';

const KINDS = Object.values(MeterKind);
const RESOURCES = Object.values(MeterResource);

/** Registers a meter in a modal; mount it only while it should be open. A plot meter has no name, the others no plot. */
export function RegisterMeterDialog({
  initialPlot,
  onRegistered,
  onClose,
}: {
  /** Starts the dialog on a plot meter of this plot. */
  initialPlot?: PickedPlot;
  onRegistered: (meter: Meter) => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const queryClient = useQueryClient();
  const today = kyivToday();
  const [kind, setKind] = useState<MeterKindValue>('PLOT');
  const [resource, setResource] = useState<MeterResourceValue>('ELECTRICITY');
  const [plot, setPlot] = useState<PickedPlot | undefined>(initialPlot);
  const [serial, setSerial] = useState('');
  const [name, setName] = useState('');
  const [installedOn, setInstalledOn] = useState(today);
  const [initialReading, setInitialReading] = useState('');
  const [note, setNote] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const isPlot = kind === 'PLOT';
  const parsed = parseMeterValue(initialReading, locale);
  const errors: Record<string, string | undefined> = {
    plotId: isPlot && !plot ? m.plotRequired : undefined,
    serial: serial.trim() === '' ? t.validation.REQUIRED : undefined,
    name: !isPlot && name.trim() === '' ? t.validation.REQUIRED : undefined,
    installedOn: installedOn === '' ? m.dateRequired : undefined,
    initialReading: parsed === undefined ? m.valueInvalid : undefined,
  };
  const clientInvalid = Object.values(errors).some((e) => e !== undefined);
  const shown = (field: string): string | undefined =>
    (submitted ? errors[field] : undefined) ?? serverErrors[field];

  const register = useRegisterMeter({
    mutation: {
      onSuccess: async (meter) => {
        await queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() });
        if (meter.plot) {
          await queryClient.invalidateQueries({ queryKey: getListPlotMetersQueryKey(meter.plot.id) });
        }
        onRegistered(meter);
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      },
    },
  });

  function edit(update: () => void) {
    setServerErrors({});
    setNotice(undefined);
    update();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (clientInvalid || parsed === undefined) return;
    const trimmedNote = note.trim();
    register.mutate({
      data: {
        kind,
        resource: isPlot ? resource : 'ELECTRICITY',
        ...(isPlot && plot ? { plotId: plot.id } : {}),
        serial: serial.trim(),
        ...(isPlot ? {} : { name: name.trim() }),
        installedOn,
        initialReading: parsed,
        ...(trimmedNote ? { note: trimmedNote } : {}),
      },
    });
  }

  return (
    <Modal title={m.registerTitle} onClose={onClose} size="lg">
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <Select
          label={m.kindField}
          value={kind}
          onChange={(e) => edit(() => setKind(e.target.value as MeterKindValue))}
        >
          {KINDS.map((k) => (
            <option key={k} value={k}>
              {m.kind[k]}
            </option>
          ))}
        </Select>
        {isPlot && (
          <Select
            label={m.resourceField}
            value={resource}
            onChange={(e) => edit(() => setResource(e.target.value as MeterResourceValue))}
          >
            {RESOURCES.map((r) => (
              <option key={r} value={r}>
                {m.resource[r]}
              </option>
            ))}
          </Select>
        )}
        {isPlot && (
          <div className="flex flex-col gap-1">
            {plot ? (
              <div className="flex flex-wrap items-center gap-3">
                <span className="text-sm font-medium text-ink-muted">{m.plotField}</span>
                <UserContent>{plot.number}</UserContent>
                <Button variant="secondary" onClick={() => edit(() => setPlot(undefined))}>
                  {m.changePlot}
                </Button>
              </div>
            ) : (
              <PlotPicker label={m.pickPlot} excludeIds={[]} onPick={(p) => edit(() => setPlot(p))} />
            )}
            {shown('plotId') && (
              <p role="alert" className="text-sm text-danger">
                {shown('plotId')}
              </p>
            )}
          </div>
        )}
        <TextField
          label={m.serial}
          value={serial}
          maxLength={50}
          error={shown('serial')}
          onChange={(e) => edit(() => setSerial(e.target.value))}
        />
        {!isPlot && (
          <TextField
            label={m.name}
            value={name}
            maxLength={100}
            error={shown('name')}
            onChange={(e) => edit(() => setName(e.target.value))}
          />
        )}
        <TextField
          label={m.installedOn}
          type="date"
          value={installedOn}
          max={today}
          error={shown('installedOn')}
          onChange={(e) => edit(() => setInstalledOn(e.target.value))}
        />
        <TextField
          label={m.initialReading}
          inputMode="decimal"
          value={initialReading}
          hint={m.valueHint}
          error={shown('initialReading')}
          onChange={(e) => edit(() => setInitialReading(e.target.value))}
        />
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
          <Button type="submit" disabled={register.isPending}>
            {register.isPending ? m.saving : m.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
