import {
  getGetMeterTreeQueryKey,
  useListMeters,
  useSetMeterParent,
  type MeterTreeMeter,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { meterLabel } from '../../meters';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { formatUser } from '../UserContent';
import { Button, Notice, Select, TextField } from '../ui';

/** Moves a meter under another parent (or out of the tree) from a date; mount it only while it should be open. */
export function MoveMeterDialog({
  meter,
  currentParentId,
  onClose,
}: {
  meter: MeterTreeMeter;
  /** Where the meter is now: the parent it is shown under, or null at the root. */
  currentParentId: string | null;
  onClose: () => void;
}) {
  const t = useT();
  const m = t.meterTree;
  const queryClient = useQueryClient();
  const today = kyivToday();
  const [parentId, setParentId] = useState(currentParentId ?? '');
  const [effectiveFrom, setEffectiveFrom] = useState(today);
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const meters = useListMeters({ resource: 'ELECTRICITY' });
  const parents = (meters.data ?? []).filter(
    (candidate) =>
      candidate.active &&
      candidate.id !== meter.id &&
      (candidate.kind === 'SUBSTATION' || candidate.kind === 'QUARTER'),
  );
  const dateError = effectiveFrom === '' ? m.dateRequired : undefined;

  const move = useSetMeterParent({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: [getGetMeterTreeQueryKey()[0]] });
        onClose();
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
    if (dateError) return;
    move.mutate({
      id: meter.id,
      data: { ...(parentId === '' ? {} : { parentMeterId: parentId }), effectiveFrom },
    });
  }

  return (
    <Modal title={formatUser(m.moveTitle, { label: meterLabel(meter) })} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <Select
          label={m.parentField}
          value={parentId}
          error={serverErrors.parentMeterId}
          onChange={(e) => edit(() => setParentId(e.target.value))}
        >
          <option value="">{m.noParent}</option>
          {parents.map((parent) => (
            <option key={parent.id} value={parent.id} data-user-content>
              {meterLabel(parent)}
            </option>
          ))}
        </Select>
        <TextField
          label={m.effectiveFrom}
          type="date"
          value={effectiveFrom}
          max={today}
          min={meter.installedOn}
          error={(submitted ? dateError : undefined) ?? serverErrors.effectiveFrom}
          onChange={(e) => edit(() => setEffectiveFrom(e.target.value))}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {m.cancel}
          </Button>
          <Button type="submit" disabled={move.isPending || parentId === (currentParentId ?? '')}>
            {move.isPending ? m.saving : m.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
