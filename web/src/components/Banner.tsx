import type { ReactNode } from 'react';

/** Inline notice: `success` is announced politely, `error` immediately. */
export function Banner({ variant, children }: { variant: 'success' | 'error'; children: ReactNode }) {
  return (
    <div
      role={variant === 'error' ? 'alert' : 'status'}
      className={`rounded-md border px-3 py-2 text-sm ${
        variant === 'error' ? 'border-danger text-danger' : 'border-success-ink text-success-ink'
      }`}
    >
      {children}
    </div>
  );
}
