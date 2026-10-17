import { useListMembers } from '@nadel/api-client';
import { keepPreviousData } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useT } from '../i18n';
import { useRoleName } from '../roleName';

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;

export function MembersPage() {
  const t = useT();
  const roleName = useRoleName();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const includeArchived = params.get('archived') === 'true';
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  const [input, setInput] = useState(q);
  const debounced = useDebouncedValue(input.trim(), SEARCH_DELAY_MS);

  // A changed search resets paging; the URL keeps the filters shareable.
  useEffect(() => {
    setParams(
      (prev) => {
        if ((prev.get('q') ?? '') === debounced) return prev;
        const next = new URLSearchParams(prev);
        if (debounced) next.set('q', debounced);
        else next.delete('q');
        next.delete('page');
        return next;
      },
      { replace: true },
    );
  }, [debounced, setParams]);

  function update(change: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next);
  }

  const query = useListMembers(
    { q: q || undefined, includeArchived: includeArchived || undefined, page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  const filtered = q !== '' || includeArchived;

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-4 px-4 py-8">
      <h1 className="font-display text-2xl font-bold">{t.members.title}</h1>
      <div className="flex flex-wrap items-center gap-4">
        <input
          type="search"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={t.members.searchPlaceholder}
          aria-label={t.members.searchPlaceholder}
          className="min-w-64 rounded-md border border-line bg-surface px-3 py-1.5 text-sm"
        />
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={includeArchived}
            onChange={(e) =>
              update((next) => {
                if (e.target.checked) next.set('archived', 'true');
                else next.delete('archived');
                next.delete('page');
              })
            }
          />
          {t.members.showArchived}
        </label>
      </div>
      <QueryBoundary
        query={query}
        isEmpty={(data) => data.items.length === 0}
        emptyMessage={filtered ? t.members.nothingFound : t.members.empty}
      >
        {(data) => (
          <>
            <ul className="flex flex-col divide-y divide-line rounded-xl border border-line bg-surface">
              {data.items.map((m) => (
                <li key={m.id}>
                  <Link
                    to={`/members/${m.id}`}
                    className="flex flex-col gap-1 px-4 py-3 hover:bg-accent-soft"
                  >
                    <span className="font-medium">
                      {[m.lastName, m.firstName, m.middleName].filter(Boolean).join(' ')}
                      {m.archived && (
                        <span className="ml-2 rounded-full border border-line px-2 py-0.5 text-xs text-muted">
                          {t.members.archived}
                        </span>
                      )}
                    </span>
                    <span className="text-sm text-muted">
                      {[m.phones.join(', '), m.email, t.channel[m.deliveryChannel]]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                    <span className="text-sm">{m.roles.map(roleName).join(', ')}</span>
                  </Link>
                </li>
              ))}
            </ul>
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              onPageChange={(p) => update((next) => next.set('page', String(p)))}
            />
          </>
        )}
      </QueryBoundary>
    </div>
  );
}
