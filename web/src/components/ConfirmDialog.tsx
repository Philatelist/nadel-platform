import { useT } from '../i18n';

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
      className="m-auto w-full max-w-sm rounded-xl border border-line bg-surface p-5 text-ink backdrop:bg-black/40"
    >
      <h2 id="confirm-title" className="font-display text-lg font-bold">
        {title}
      </h2>
      <p className="mt-2 text-sm text-muted">{message}</p>
      <div className="mt-4 flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-md border border-line px-3 py-1.5 text-sm"
        >
          {t.memberForm.cancel}
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={busy}
          className="rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-on-accent disabled:opacity-60"
        >
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
