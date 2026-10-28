import { useListPlots } from '@nadel/api-client';
import { useId, useState, type KeyboardEvent } from 'react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { UserContent } from '../UserContent';
import { useT } from '../../i18n';
import { SearchField } from '../ui';

export interface PickedPlot {
  id: string;
  number: string;
}

const DEBOUNCE_MS = 300;

/**
 * Picks one plot by a debounced number search; common land, archived plots and `excludeIds` are never offered.
 * An accessible combobox: the suggestions are a popover laid over the content (the field keeps its height), closed
 * until the user types or presses ArrowDown, and closed again by Escape, a pick or leaving the field.
 */
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
  const listId = useId();
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const q = useDebouncedValue(text.trim(), DEBOUNCE_MS);
  const search = useListPlots({ q: q || undefined, kind: 'PLOT', size: 10 });
  const results = (search.data?.items ?? []).filter(
    (p) => p.kind !== 'COMMON' && !p.archived && !excludeIds.includes(p.id),
  );
  const showList = open && search.data !== undefined;
  const optionId = (index: number) => `${listId}-option-${index}`;

  function pick(index: number) {
    const plot = results[index];
    if (plot === undefined) return;
    onPick({ id: plot.id, number: plot.number });
    setText('');
    setOpen(false);
    setActive(-1);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) setOpen(true);
      else setActive((current) => Math.min(current + 1, results.length - 1));
    } else if (event.key === 'ArrowUp' && open) {
      event.preventDefault();
      setActive((current) => Math.max(current - 1, 0));
    } else if (event.key === 'Enter' && open && active >= 0) {
      event.preventDefault();
      pick(active);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();
      setOpen(false);
      setActive(-1);
    }
  }

  return (
    <div
      className="relative"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <SearchField
        label={label}
        placeholder={t.tariffs.scope.searchPlot}
        value={text}
        role="combobox"
        autoComplete="off"
        aria-expanded={showList}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showList && active >= 0 ? optionId(active) : undefined}
        onKeyDown={onKeyDown}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActive(-1);
        }}
      />
      {showList && (
        <ul
          id={listId}
          role="listbox"
          aria-label={label}
          className="absolute inset-x-0 top-full z-20 mt-1 max-h-60 overflow-y-auto rounded-control border border-line bg-surface shadow-lg"
        >
          {results.map((p, index) => (
            <li
              key={p.id}
              id={optionId(index)}
              role="option"
              aria-selected={index === active}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => pick(index)}
              className={`flex min-h-11 cursor-pointer items-center px-3 hover:bg-accent-soft ${
                index === active ? 'bg-accent-soft' : ''
              }`}
            >
              <UserContent>{p.street ? `${p.number} · ${p.street}` : p.number}</UserContent>
            </li>
          ))}
          {results.length === 0 && (
            <li role="presentation" className="px-3 py-2 text-sm text-ink-muted">
              {t.tariffs.scope.noPlotsFound}
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
