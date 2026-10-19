import { useId, type ReactNode } from 'react';

const widths = { sm: 'max-w-sm', lg: 'max-w-xl' } as const;

/** Modal on a native `<dialog>`; mount it only while it should be open. Escape calls `onClose`. */
export function Modal({
  title,
  onClose,
  size = 'sm',
  children,
}: {
  title: string;
  onClose: () => void;
  size?: keyof typeof widths;
  children: ReactNode;
}) {
  const titleId = useId();
  return (
    <dialog
      ref={(el) => {
        if (el && !el.open) el.showModal();
      }}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      aria-labelledby={titleId}
      className={`m-auto max-h-[90vh] w-full ${widths[size]} overflow-y-auto rounded-panel border border-line bg-surface p-5 text-ink backdrop:bg-ink/40`}
    >
      <h2 id={titleId} className="font-serif text-xl font-semibold">
        {title}
      </h2>
      {children}
    </dialog>
  );
}
