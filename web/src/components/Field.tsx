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
  children,
}: {
  label: string;
  error?: string;
  children: (control: ControlProps) => ReactNode;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm text-muted">
        {label}
      </label>
      {children({ id, invalid: error !== undefined, 'aria-describedby': error ? errorId : undefined })}
      {error && (
        <p id={errorId} role="alert" className="text-sm text-status-red">
          {error}
        </p>
      )}
    </div>
  );
}
