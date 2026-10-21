import { useListPlots } from '@nadel/api-client';
import { useState } from 'react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { UserContent } from '../UserContent';
import { useT } from '../../i18n';
import { SearchField } from '../ui';

export interface PickedPlot {
  id: string;
  number: string;
}

const DEBOUNCE_MS = 300;

/** Picks one plot by a debounced number search; common land, archived plots and `excludeIds` are never offered. */
export function PlotPicker({
  label,
  excludeIds,
  onPick,
}: {
  label: string;
  excludeIds: readonly string[];
  onPick: (plot: PickedPlot) => void;
}) {
  const t = useT();
  const [text, setText] = useState('');
  const q = useDebouncedValue(text.trim(), DEBOUNCE_MS);
  const search = useListPlots({ q: q || undefined, kind: 'PLOT', size: 10 });
  const results = (search.data?.items ?? []).filter(
    (p) => p.kind !== 'COMMON' && !p.archived && !excludeIds.includes(p.id),
  );
  return (
    <div className="flex flex-col gap-2">
      <SearchField
        label={label}
        placeholder={t.tariffs.scope.searchPlot}
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      {search.data && results.length === 0 && (
        <p className="text-sm text-ink-muted">{t.tariffs.scope.noPlotsFound}</p>
      )}
      {results.length > 0 && (
        <ul className="flex max-h-60 flex-col overflow-y-auto rounded-control border border-line">
          {results.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                onClick={() => {
                  onPick({ id: p.id, number: p.number });
                  setText('');
                }}
                className="min-h-11 w-full px-3 text-left hover:bg-accent-soft"
              >
                <UserContent>{p.number}</UserContent>
                {p.street && (
                  <>
                    {' · '}
                    <UserContent>{p.street}</UserContent>
                  </>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
