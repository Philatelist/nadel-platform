import type { ReactNode } from 'react';

export type BadgeTone = 'success' | 'info' | 'warning' | 'neutral';

const tones: Record<BadgeTone, string> = {
  success: 'bg-success-soft text-success-ink',
  info: 'bg-info-soft text-info-ink',
  warning: 'bg-warning-soft text-warning-ink',
  neutral: 'bg-accent-soft text-ink-muted',
};

export function Badge({ tone, children }: { tone: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[13px] font-semibold ${tones[tone]}`}
    >
      {children}
    </span>
  );
}
