import { useState, type ReactNode } from 'react';
import { useT } from '../i18n';

export interface PlotTab {
  id: string;
  label: string;
  content: ReactNode;
}

/**
 * The sections of a plot card. A new section is one more entry in `tabs` (a falsy entry is skipped, so a
 * permission-gated tab is written `cond && {...}`). With a single section the tab bar is not shown.
 */
export function PlotTabs({ tabs }: { tabs: readonly (PlotTab | false | null | undefined)[] }) {
  const t = useT();
  const visible = tabs.filter((tab): tab is PlotTab => Boolean(tab));
  const [selected, setSelected] = useState(visible[0]?.id);
  const current = visible.find((tab) => tab.id === selected) ?? visible[0];
  if (current === undefined) return null;
  return (
    <>
      {visible.length > 1 && (
        <div role="tablist" aria-label={t.plot.tabs.label} className="flex gap-1 border-b border-line-subtle">
          {visible.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={`plot-tab-${tab.id}`}
              aria-selected={tab.id === current.id}
              aria-controls="plot-tabpanel"
              onClick={() => setSelected(tab.id)}
              className={`-mb-px border-b-2 px-4 py-2 font-medium ${
                tab.id === current.id
                  ? 'border-accent text-ink'
                  : 'border-transparent text-ink-muted hover:text-ink'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      )}
      <div
        role={visible.length > 1 ? 'tabpanel' : undefined}
        id="plot-tabpanel"
        aria-labelledby={visible.length > 1 ? `plot-tab-${current.id}` : undefined}
        className="flex flex-col gap-4"
      >
        {current.content}
      </div>
    </>
  );
}
