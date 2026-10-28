import {
  PlotKind,
  PlotStatus,
  useListPlots,
  type PlotKind as PlotKindValue,
  type PlotStatus as PlotStatusValue,
  type PlotSummary,
} from '@nadel/api-client';
import { keepPreviousData } from '@tanstack/react-query';
import { format, formatSotky } from '@nadel/shared';
import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import {
  Badge,
  Button,
  DataTable,
  PageHeader,
  Panel,
  SearchField,
  Select,
  StatusDot,
  type Column,
} from '../components/ui';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useCanEditPlots } from '../hooks/useCanEditPlots';
import { plotStatusLabel } from '../plotStatus';
import { useDocumentTitle } from '../useDocumentTitle';

const MAX_OWNER_NAMES = 2;

/** Up to two owner names joined by a comma, then "+N" for the rest; nothing for unowned or common land. */
function OwnerNames({ owners }: { owners: PlotSummary['owners'] }) {
  const t = useT();
  if (!owners || owners.length === 0) return null;
  const names = owners.slice(0, MAX_OWNER_NAMES).map((o) => o.name);
  const more = owners.length - MAX_OWNER_NAMES;
  return (
    <>
      {names.map((name, index) => (
        <span key={`${name}-${index}`}>
          {index > 0 && ', '}
          <UserContent>{name}</UserContent>
        </span>
      ))}
      {more > 0 && ` ${format(t.plots.ownersMore, { count: more })}`}
    </>
  );
}

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;

const KINDS = Object.values(PlotKind);
const STATUSES = Object.values(PlotStatus);

function oneOf<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((v) => v === value);
}

export function PlotsPage() {
  const t = useT();
  const { locale } = useLocale();
  useDocumentTitle(t.plots.title);
  const canView = useCan('PLOTS_VIEW');
  const canEdit = useCanEditPlots();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const kind: PlotKindValue | undefined = oneOf(KINDS, params.get('kind'));
  const status: PlotStatusValue | undefined = oneOf(STATUSES, params.get('status'));
  const includeArchived = canView && params.get('archived') === 'true';
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  const [input, setInput] = useState(q);

  // A changed search resets paging where there is one; the URL keeps the filters shareable.
  useDebouncedSearchParam('q', input, SEARCH_DELAY_MS, { resetPage: true });

  function update(change: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params);
    change(next);
    next.delete('page');
    setParams(next);
  }

  const query = useListPlots(
    {
      q: q || undefined,
      kind,
      status,
      includeArchived: includeArchived || undefined,
      page,
      size: PAGE_SIZE,
    },
    { query: { placeholderData: keepPreviousData } },
  );
  const filtered = q !== '' || kind !== undefined || status !== undefined || includeArchived;

  const c = t.plots.columns;
  const numberColumn: Column<PlotSummary> = {
    key: 'number',
    header: c.number,
    cell: (p) => (
      <Link to={`/plots/${p.id}`} className="font-medium text-accent underline">
        <UserContent>{p.number}</UserContent>
      </Link>
    ),
  };
  const streetColumn: Column<PlotSummary> = {
    key: 'street',
    header: c.street,
    cell: (p) => (p.street ? <UserContent>{p.street}</UserContent> : t.plot.fields.none),
  };
  const kindColumn: Column<PlotSummary> = { key: 'kind', header: c.kind, cell: (p) => t.plot.kind[p.kind] };
  const statusColumn: Column<PlotSummary> = {
    key: 'status',
    header: c.status,
    cell: (p) => <StatusDot status={p.status} label={plotStatusLabel(t, p)} />,
  };
  const columns: Column<PlotSummary>[] = canView
    ? [
        numberColumn,
        streetColumn,
        {
          key: 'area',
          header: c.area,
          numeric: true,
          cell: (p) => (p.areaM2 === undefined ? t.plot.fields.none : formatSotky(p.areaM2, locale)),
        },
        {
          key: 'cadastralNumber',
          header: c.cadastralNumber,
          cell: (p) =>
            p.cadastralNumber ? <UserContent>{p.cadastralNumber}</UserContent> : t.plot.fields.none,
        },
        kindColumn,
        statusColumn,
        {
          key: 'owners',
          header: c.owners,
          cell: (p) => <OwnerNames owners={p.owners} />,
        },
        {
          key: 'archived',
          header: c.archived,
          cell: (p) => (p.archived ? <Badge tone="neutral">{t.plots.archived}</Badge> : null),
        },
      ]
    : [numberColumn, streetColumn, kindColumn, statusColumn];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.plots.title}
        actions={canEdit && <Button onClick={() => void navigate('/plots/new')}>{t.plots.add}</Button>}
      />
      <Panel>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <div className="min-w-64 flex-1 sm:max-w-md">
            <SearchField
              label={canView ? t.plots.searchPlaceholder : t.plots.searchPlaceholderRestricted}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={canView ? t.plots.searchPlaceholder : t.plots.searchPlaceholderRestricted}
            />
          </div>
          <Select
            label={t.plots.filterKind}
            value={kind ?? ''}
            onChange={(e) =>
              update((next) => {
                if (e.target.value) next.set('kind', e.target.value);
                else next.delete('kind');
              })
            }
          >
            <option value="">{t.plots.allKinds}</option>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t.plot.kind[k]}
              </option>
            ))}
          </Select>
          <Select
            label={t.plots.filterStatus}
            value={status ?? ''}
            onChange={(e) =>
              update((next) => {
                if (e.target.value) next.set('status', e.target.value);
                else next.delete('status');
              })
            }
          >
            <option value="">{t.plots.allStatuses}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s === 'GREY' ? `${t.status.GREY_PLOT} / ${t.status.GREY_COMMON}` : t.status[s]}
              </option>
            ))}
          </Select>
          {canView && (
            <label className="flex min-h-11 items-center gap-2">
              <input
                type="checkbox"
                checked={includeArchived}
                onChange={(e) =>
                  update((next) => {
                    if (e.target.checked) next.set('archived', 'true');
                    else next.delete('archived');
                  })
                }
              />
              {t.plots.showArchived}
            </label>
          )}
        </div>
        <QueryBoundary
          query={query}
          isEmpty={(data) => data.items.length === 0}
          emptyMessage={filtered ? t.plots.nothingFound : t.plots.empty}
        >
          {(data) => (
            <>
              <DataTable
                columns={columns}
                rows={data.items}
                rowKey={(p) => p.id}
                caption={t.plots.title}
                emptyMessage={t.plots.empty}
              />
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                onPageChange={(p) => {
                  const next = new URLSearchParams(params);
                  next.set('page', String(p));
                  setParams(next);
                }}
              />
            </>
          )}
        </QueryBoundary>
      </Panel>
    </div>
  );
}
