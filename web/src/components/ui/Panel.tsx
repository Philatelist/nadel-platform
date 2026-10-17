import type { ReactNode } from 'react';

export function Panel({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3.5 rounded-panel border border-line bg-surface p-5">
      {title && <h2 className="text-[17px] font-semibold text-ink">{title}</h2>}
      {children}
    </section>
  );
}
