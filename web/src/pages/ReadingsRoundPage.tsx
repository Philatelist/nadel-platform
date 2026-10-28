import {
  MeterResource,
  useGetReadingRound,
  type MeterResource as MeterResourceValue,
} from '@nadel/api-client';
import { useCallback, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { ReadingDateField } from '../components/readings/ReadingDateField';
import { PageHeader, Panel, SearchField, Select, TextField } from '../components/ui';
import { RoundCard } from '../components/readings/RoundCard';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useT } from '../i18n';
import { kyivDaysAgo, kyivToday } from '../kyivToday';
import { groupRound, readStoredResource, storeResource } from '../readingsRound';
import { useDocumentTitle } from '../useDocumentTitle';

const RESOURCES = Object.values(MeterResource);
const SEARCH_DELAY_MS = 300;
/** "Без показу з" starts this many days back when it is switched on. */
const NO_READING_DEFAULT_DAYS = 60;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

function oneOf<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((v) => v === value);
}

/** The electrician's screen: one round date for the whole list, a card per meter, saved one by one. */
export function ReadingsRoundPage() {
  const t = useT();
  const r = t.readings;
  const m = t.meters;
  useDocumentTitle(r.title);
  const [params, setParams] = useSearchParams();
  // the URL wins; without it the choice this browser remembered (any storage failure means "all")
  const resourceParam = params.get('resource');
  const [stored] = useState(readStoredResource);
  const resource: MeterResourceValue | undefined =
    resourceParam === null ? stored : oneOf(RESOURCES, resourceParam);
  const street = params.get('street') ?? '';
  const sinceParam = params.get('noReadingSince');
  const noReadingSince = sinceParam !== null && DAY.test(sinceParam) ? sinceParam : undefined;
  // undefined = the round date was not touched: it is today and the server dates the readings itself
  const [roundDate, setRoundDate] = useState<string>();

  const [input, setInput] = useState(street);

  // A changed search resets paging where there is one; the URL keeps the filters shareable.
  useDebouncedSearchParam('street', input, SEARCH_DELAY_MS);

  function update(change: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next, { replace: true });
  }

  const query = useGetReadingRound({
    resource,
    street: street || undefined,
    noReadingSince,
  });
  const filtered = resource !== undefined || street !== '' || noReadingSince !== undefined;

  const list = useRef<HTMLUListElement>(null);
  /** Enter on a saved row moves the cursor to the value field of the next card. */
  const focusNext = useCallback((current: HTMLInputElement | null) => {
    const fields = Array.from(
      list.current?.querySelectorAll<HTMLInputElement>('input[inputmode="decimal"]') ?? [],
    );
    const index = current ? fields.indexOf(current) : -1;
    if (index >= 0) fields[index + 1]?.focus();
  }, []);

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={r.title} />
      <Panel>
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <ReadingDateField label={r.roundDate} value={roundDate} onChange={setRoundDate} />
          <Select
            label={m.filterResource}
            value={resource ?? ''}
            onChange={(e) => {
              const chosen = oneOf(RESOURCES, e.target.value);
              storeResource(chosen);
              update((next) => {
                // "all" is written to the URL too, so it wins over the remembered value until the next visit
                next.set('resource', chosen ?? 'all');
              });
            }}
          >
            <option value="">{m.allResources}</option>
            {RESOURCES.map((value) => (
              <option key={value} value={value}>
                {m.resource[value]}
              </option>
            ))}
          </Select>
          <div className="min-w-48">
            <SearchField
              label={m.filterStreet}
              value={input}
              maxLength={200}
              onChange={(e) => setInput(e.target.value)}
              placeholder={m.filterStreet}
            />
          </div>
          <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
            <label className="flex min-h-11 items-center gap-2 sm:mt-5">
              <input
                type="checkbox"
                checked={noReadingSince !== undefined}
                onChange={(e) =>
                  update((next) => {
                    if (e.target.checked) next.set('noReadingSince', kyivDaysAgo(NO_READING_DEFAULT_DAYS));
                    else next.delete('noReadingSince');
                  })
                }
              />
              {r.noReadingSince}
            </label>
            {noReadingSince !== undefined && (
              <TextField
                label={r.noReadingSinceDate}
                type="date"
                value={noReadingSince}
                max={kyivToday()}
                onChange={(e) =>
                  update((next) => {
                    if (DAY.test(e.target.value)) next.set('noReadingSince', e.target.value);
                  })
                }
              />
            )}
          </div>
        </div>
      </Panel>
      <QueryBoundary
        query={query}
        isEmpty={(data) => data.length === 0}
        emptyMessage={filtered ? r.nothingFound : r.empty}
      >
        {(meters) => (
          <ul ref={list} aria-label={r.listLabel} className="flex flex-col gap-3">
            {groupRound(meters).map((group) => (
              <RoundCard key={group.key} group={group} roundDate={roundDate} onSaved={focusNext} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </div>
  );
}
