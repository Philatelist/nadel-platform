import {
  PlotKind,
  getGetPlotQueryKey,
  getGetPlotMapQueryKey,
  getListPlotsQueryKey,
  useCreatePlot,
  useUpdatePlot,
  type PlotDetails,
  type PlotInput,
} from '@nadel/api-client';
import { m2ToSotky, toSquareMetres, type AreaUnit } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../problem';
import { Button, Notice, Panel, Select, TextArea, TextField } from './ui';

const KINDS = Object.values(PlotKind);
const CADASTRAL = /^\d{10}:\d{2}:\d{3}:\d{4}$/;

function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/** Re-expresses the typed area in another unit; text that is not a valid area stays as typed. */
export function convertAreaText(value: string, from: AreaUnit, to: AreaUnit): string {
  const m2 = toSquareMetres(value, from);
  if (m2 === undefined) return value;
  return to === 'M2' ? m2 : m2ToSotky(m2);
}

/**
 * Create / edit form for a plot. Initial values come from `plot` once, so mount it with a `key`
 * (for example the plot id) instead of syncing state in effects.
 */
export function PlotForm({
  plot,
  onSaved,
  onCancel,
}: {
  plot?: PlotDetails;
  onSaved: (saved: PlotDetails) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const f = t.plot.fields;
  const queryClient = useQueryClient();

  const [number, setNumber] = useState(plot?.number ?? '');
  const [street, setStreet] = useState(plot?.street ?? '');
  const [kind, setKind] = useState<PlotInput['kind']>(plot?.kind ?? PlotKind.PLOT);
  const [unit, setUnit] = useState<AreaUnit>('SOTKA');
  const [area, setArea] = useState(plot?.areaM2 === undefined ? '' : m2ToSotky(plot.areaM2));
  const [cadastralNumber, setCadastralNumber] = useState(plot?.cadastralNumber ?? '');
  const [hasBuilding, setHasBuilding] = useState(plot?.hasBuilding ?? false);
  const [notes, setNotes] = useState(plot?.notes ?? '');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string>();

  async function handleSaved(saved: PlotDetails) {
    queryClient.setQueryData(getGetPlotQueryKey(saved.id), saved);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetPlotMapQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetPlotQueryKey(saved.id) }),
    ]);
    onSaved(saved);
  }

  function handleError(error: unknown) {
    const fieldErrors = problemFieldErrors(t, error);
    setErrors(fieldErrors);
    setFormError(Object.keys(fieldErrors).length === 0 ? problemMessage(t, error) : undefined);
  }

  const create = useCreatePlot({ mutation: { onSuccess: handleSaved, onError: handleError } });
  const update = useUpdatePlot({ mutation: { onSuccess: handleSaved, onError: handleError } });
  const isPending = create.isPending || update.isPending;

  function clearError(key: string) {
    setErrors((prev) => {
      if (!(key in prev)) return prev;
      return Object.fromEntries(Object.entries(prev).filter(([k]) => k !== key));
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(undefined);
    const clientErrors: Record<string, string> = {};
    if (number.trim() === '') clientErrors.number = t.validation.REQUIRED_number;
    if (cadastralNumber.trim() !== '' && !CADASTRAL.test(cadastralNumber.trim())) {
      clientErrors.cadastralNumber = t.validation.CADASTRAL_INVALID;
    }
    const areaM2 = toSquareMetres(area, unit);
    if (area.trim() === '') clientErrors.areaM2 = t.validation.REQUIRED_areaM2;
    else if (areaM2 === undefined || (kind === PlotKind.PLOT && Number(areaM2) <= 0)) {
      clientErrors.areaM2 = t.validation.AREA_INVALID;
    }
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0 || areaM2 === undefined) return;

    // The API field is a JSON number; the value is already a validated decimal with at most two decimals.
    const data: PlotInput = {
      number: number.trim(),
      street: optional(street),
      kind,
      areaM2: Number(areaM2),
      cadastralNumber: optional(cadastralNumber),
      hasBuilding,
      notes: optional(notes),
    };
    if (plot) update.mutate({ id: plot.id, data });
    else create.mutate({ data });
  }

  return (
    <Panel>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        {formError && <Notice tone="danger">{formError}</Notice>}

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <TextField
            label={f.number}
            error={errors.number}
            value={number}
            maxLength={20}
            onChange={(e) => {
              setNumber(e.target.value);
              clearError('number');
            }}
          />
          <TextField
            label={f.street}
            error={errors.street}
            value={street}
            maxLength={100}
            onChange={(e) => {
              setStreet(e.target.value);
              clearError('street');
            }}
          />
          <Select
            label={f.kind}
            error={errors.kind}
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as PlotInput['kind']);
              clearError('areaM2');
            }}
          >
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t.plot.kind[k]}
              </option>
            ))}
          </Select>
          <div className="grid grid-cols-[1fr_auto] gap-2">
            <TextField
              label={f.area}
              error={errors.areaM2}
              inputMode="decimal"
              value={area}
              onChange={(e) => {
                setArea(e.target.value);
                clearError('areaM2');
              }}
            />
            <Select
              label={t.plotForm.areaUnit}
              value={unit}
              onChange={(e) => {
                const next = e.target.value as AreaUnit;
                setArea((prev) => convertAreaText(prev, unit, next));
                setUnit(next);
                clearError('areaM2');
              }}
            >
              <option value="SOTKA">{t.plotForm.unitSotka}</option>
              <option value="M2">{t.plotForm.unitM2}</option>
            </Select>
          </div>
          <TextField
            label={f.cadastralNumber}
            error={errors.cadastralNumber}
            value={cadastralNumber}
            maxLength={22}
            placeholder={t.plotForm.cadastralPlaceholder}
            onChange={(e) => {
              setCadastralNumber(e.target.value);
              clearError('cadastralNumber');
            }}
          />
          <label className="flex min-h-11 items-center gap-2 self-end">
            <input type="checkbox" checked={hasBuilding} onChange={(e) => setHasBuilding(e.target.checked)} />
            {t.plotForm.hasBuilding}
          </label>
          <div className="md:col-span-2">
            <TextArea
              label={f.notes}
              error={errors.notes}
              rows={3}
              value={notes}
              maxLength={2000}
              onChange={(e) => {
                setNotes(e.target.value);
                clearError('notes');
              }}
            />
          </div>
        </div>

        <div className="flex gap-2">
          <Button type="submit" disabled={isPending}>
            {isPending ? t.plotForm.saving : t.plotForm.save}
          </Button>
          <Button variant="secondary" onClick={onCancel}>
            {t.plotForm.cancel}
          </Button>
        </div>
      </form>
    </Panel>
  );
}
