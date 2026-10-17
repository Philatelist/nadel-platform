import type { ReactNode } from 'react';

export type NoticeTone = 'warning' | 'success' | 'danger';

const tones: Record<NoticeTone, string> = {
  warning: 'border-warning-line bg-warning-soft text-warning-ink',
  success: 'border-success-ink bg-success-soft text-success-ink',
  danger: 'border-danger bg-danger-soft text-danger',
};

/** Inline notice: `danger` is announced immediately, the others politely. */
export function Notice({ tone, children }: { tone: NoticeTone; children: ReactNode }) {
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={`rounded-control border px-4 py-3 text-sm ${tones[tone]}`}
    >
      {children}
    </div>
  );
}
