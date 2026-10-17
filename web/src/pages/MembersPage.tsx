import { useListMembers, type Member } from '@nadel/api-client';
import { keepPreviousData } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, buttonClass, DataTable, PageHeader, Panel, SearchField, type Column } from '../components/ui';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useRoleName } from '../roleName';

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;

export function MembersPage() {
  const t = useT();
  const roleName = useRoleName();
  const canEdit = useCan('MEMBERS_EDIT');
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

  const fullName = (m: Member) => [m.lastName, m.firstName, m.middleName].filter(Boolean).join(' ');
  const f = t.member.fields;
  const columns: Column<Member>[] = [
    {
      key: 'name',
      header: f.name,
      cell: (m) => (
        <Link to={`/members/${m.id}`} className="font-medium text-accent underline">
          {fullName(m)}
        </Link>
      ),
    },
    { key: 'phones', header: f.phones, cell: (m) => m.phones.join(', ') || f.none },
    { key: 'email', header: f.email, cell: (m) => m.email ?? f.none },
    { key: 'channel', header: f.deliveryChannel, cell: (m) => t.channel[m.deliveryChannel] },
    { key: 'roles', header: f.roles, cell: (m) => m.roles.map(roleName).join(', ') || f.none },
    {
      key: 'status',
      header: t.members.columnStatus,
      cell: (m) => (
        <Badge tone={m.archived ? 'neutral' : 'success'}>
          {m.archived ? t.members.archived : t.members.active}
        </Badge>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.members.title}
        actions={
          canEdit && (
            <Link to="/members/new" className={buttonClass('primary')}>
              {t.members.add}
            </Link>
          )
        }
      />
      <Panel>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <div className="min-w-64 flex-1 sm:max-w-md">
            <SearchField
              label={t.members.searchPlaceholder}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={t.members.searchPlaceholder}
            />
          </div>
          <label className="flex min-h-11 items-center gap-2">
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
              <DataTable
                columns={columns}
                rows={data.items}
                rowKey={(m) => m.id}
                caption={t.members.title}
                emptyMessage={t.members.empty}
              />
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                onPageChange={(p) => update((next) => next.set('page', String(p)))}
              />
            </>
          )}
        </QueryBoundary>
      </Panel>
    </div>
  );
}
