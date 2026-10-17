import { useT } from '../i18n';
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
  title: string;
  message: string;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  return (
    <dialog
      ref={(el) => {
        if (el && !el.open) el.showModal();
      }}
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
      aria-labelledby="confirm-title"
      className="m-auto w-full max-w-sm rounded-panel border border-line bg-surface p-5 text-ink backdrop:bg-ink/40"
    >
      <h2 id="confirm-title" className="font-serif text-xl font-semibold">
        {title}
      </h2>
      <p className="mt-2 text-[15px] text-ink-muted">{message}</p>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>
          {t.memberForm.cancel}
        </Button>
        <Button onClick={onConfirm} disabled={busy}>
          {confirmLabel}
        </Button>
      </div>
    </dialog>
  );
}
