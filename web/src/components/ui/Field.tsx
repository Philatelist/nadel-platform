import { useId, type ReactNode } from 'react';

export interface ControlProps {
  id: string;
  invalid: boolean;
  'aria-describedby': string | undefined;
}

/** A labelled form control with an inline error (`role="alert"`, linked through `aria-describedby`). */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  /** Must resolve to text from the i18n catalogs (or user content); never a literal string, the lint rule cannot see props. */
  label: ReactNode;
  error?: string;
  hint?: string;
  children: (control: ControlProps) => ReactNode;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errorId : undefined, hint ? hintId : undefined].filter(Boolean).join(' ');
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink-muted">
        {label}
      </label>
      {children({ id, invalid: error !== undefined, 'aria-describedby': describedBy || undefined })}
      {hint && (
        <p id={hintId} className="text-[13px] text-ink-subtle">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
