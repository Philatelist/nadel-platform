import type { ReactNode } from 'react';
import { Badge, Button } from '../ui';
import { useT } from '../../i18n';

/** The fixed tax report slot while no template is in it. */
export function TaxSlotEmpty({ onUpload, children }: { onUpload: () => void; children?: ReactNode }) {
  const s = useT().reportTemplates;
  return (
    <section
      aria-label={s.tax.title}
      className="flex flex-col gap-3 rounded-panel border border-line bg-surface p-5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-[17px] font-semibold">{s.tax.title}</h2>
        <Badge tone="warning">{s.tax.notAdded}</Badge>
      </div>
      <p className="text-ink-muted">{s.tax.hint}</p>
      <div>
        <Button onClick={onUpload}>{s.tax.upload}</Button>
      </div>
      {children}
    </section>
  );
}
