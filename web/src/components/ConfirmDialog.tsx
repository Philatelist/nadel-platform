import type { ReactNode } from 'react';
import { useT } from '../i18n';
import { Modal } from './Modal';
import { Button } from './ui';

/** Modal confirmation on a native `<dialog>`; mount it only while it should be open. */
export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  busy,
  onConfirm,
  onCancel,
}: {
  title: ReactNode;
  message: ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  return (
    <Modal title={title} onClose={onCancel}>
      <p className="mt-2 text-[15px] text-ink-muted">{message}</p>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>
          {t.memberForm.cancel}
        </Button>
        <Button onClick={onConfirm} disabled={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
