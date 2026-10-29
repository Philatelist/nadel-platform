import { format } from '@nadel/shared';
import { useState } from 'react';
import { useT } from '../../i18n';
import { Modal } from '../Modal';
import { Button, TextArea } from '../ui';

export const MAX_REASON = 500;

/** Asks for the required reason (1-500 characters) before a certificate is annulled; mount only while open. */
export function AnnulDialog({
  number,
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  number: string;
  busy: boolean;
  /** A server-side message to show under the reason (field or general). */
  error?: string;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const c = t.certificates;
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const trimmed = reason.trim();
  const local = touched && trimmed === '' ? t.validation.REQUIRED_reason : undefined;
  return (
    <Modal title={format(c.annulTitle, { number })} onClose={onCancel}>
      <p className="mt-2 text-[15px] text-ink-muted">{c.annulText}</p>
      <div className="mt-3">
        <TextArea
          label={c.annulReasonLabel}
          value={reason}
          maxLength={MAX_REASON}
          rows={3}
          onChange={(e) => setReason(e.target.value)}
          error={local ?? error}
        />
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>
          {t.memberForm.cancel}
        </Button>
        <Button
          disabled={busy}
          onClick={() => {
            setTouched(true);
            if (trimmed !== '') onConfirm(trimmed);
          }}
        >
          {c.annulConfirm}
        </Button>
      </div>
    </Modal>
  );
}
