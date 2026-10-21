import type { ChargeType } from '@nadel/api-client';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { chargeTypeName } from '../../chargeTypeName';
import { Modal } from '../Modal';
import { Button, Notice, TextArea, TextField } from '../ui';

export interface ChargeTypeFormValues {
  name: string;
  description: string;
  active: boolean;
}

/**
 * Add (no `type`) or edit form in a modal; mount it only while it should be open. A starting type that is not
 * renamed shows its localised default name, and that name is sent back unchanged (the server treats it as no rename).
 */
export function ChargeTypeDialog({
  type,
  busy,
  error,
  onSubmit,
  onCancel,
}: {
  type?: ChargeType;
  busy: boolean;
  error?: string;
  onSubmit: (values: ChargeTypeFormValues) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const d = t.chargeTypes.dialog;
  const [name, setName] = useState(type ? chargeTypeName(type, t) : '');
  const [description, setDescription] = useState(type?.description ?? '');
  const [active, setActive] = useState(type?.active ?? true);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({ name: name.trim(), description: description.trim(), active });
  }

  return (
    <Modal title={type ? d.editTitle : d.addTitle} onClose={onCancel} size="lg">
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit}>
        <TextField
          label={d.name}
          value={name}
          maxLength={100}
          required
          onChange={(e) => setName(e.target.value)}
        />
        <TextArea
          label={d.description}
          value={description}
          maxLength={500}
          rows={3}
          onChange={(e) => setDescription(e.target.value)}
        />
        {type && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
            />
            <span>
              <span className="font-medium">{d.active}</span>
              <span className="block text-ink-muted">{d.activeHint}</span>
            </span>
          </label>
        )}
        {error && <Notice tone="danger">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onCancel}>
            {d.cancel}
          </Button>
          <Button type="submit" disabled={busy || name.trim() === ''}>
            {busy ? d.saving : d.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
