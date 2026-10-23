import { useListAuditEntries, type AuditEntityType, type AuditEntry } from '@nadel/api-client';
import { formatDateTime } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { DataTable, PageHeader, Panel, Select, TextField, type Column } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { UserContent } from '../components/UserContent';
import { auditRecordLabel, auditValue } from '../auditValue';
import { problemCode, problemFieldErrors } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 50;
const ENTITY_TYPES = [
  'MEMBER',
  'ROLE',
  'PLOT',
  'SETTINGS',
  'OWNERSHIP',
  'CHARGE_TYPE',
  'TARIFF',
  'METER',
  'METER_READING',
  'CHARGE',
  'LOSSES_RUN',
  'COMMON_CONSUMER_RUN',
] as const;

/** Where a record of an entity type is shown; absent for types without a page of their own. */
const entityHref: Readonly<Partial<Record<AuditEntityType, (entityId: string) => string>>> = {
  MEMBER: (id) => `/members/${id}`,
  PLOT: (id) => `/plots/${id}`,
  OWNERSHIP: (id) => `/plots/${id}`,
  CHARGE_TYPE: () => '/charge-types',
  TARIFF: (id) => `/tariffs/${id}`,
  METER: (id) => `/meters/${id}`,
  // the entity id of a reading's record is its meter
  METER_READING: (id) => `/meters/${id}`,
  // the entity id of a charge's record is its plot
  CHARGE: (id) => `/plots/${id}`,
  LOSSES_RUN: (id) => `/charges/losses?run=${id}`,
  COMMON_CONSUMER_RUN: (id) => `/charges/common?run=${id}`,
};
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function isEntityType(value: string | null): value is (typeof ENTITY_TYPES)[number] {
  return ENTITY_TYPES.some((type) => type === value);
}

export function AuditPage() {
  const t = useT();
  const { locale } = useLocale();
  const [params, setParams] = useSearchParams();
  const entityTypeParam = params.get('entityType');
  const entityType = isEntityType(entityTypeParam) ? entityTypeParam : undefined;
  const entityId = params.get('entityId') ?? undefined;
  const actorMemberId = params.get('actorMemberId') ?? undefined;
  const fromParam = params.get('from') ?? '';
  const toParam = params.get('to') ?? '';
  const from = DATE_PATTERN.test(fromParam) ? fromParam : undefined;
  const to = DATE_PATTERN.test(toParam) ? toParam : undefined;
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  // Any filter change resets paging; the URL keeps the filters shareable.
  function setFilter(key: string, value: string | undefined) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    next.delete('page');
    setParams(next);
  }

  const query = useListAuditEntries(
    { entityType, entityId, actorMemberId, from, to, page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  const filtered = Boolean(entityType ?? entityId ?? actorMemberId ?? from ?? to);
  const dateError =
    problemCode(query.error) === 'VALIDATION_FAILED'
      ? (problemFieldErrors(t, query.error).from ??
        problemFieldErrors(t, query.error).to ??
        t.problem.VALIDATION_FAILED)
      : undefined;
  const a = t.audit;
  useDocumentTitle(a.title);

  const actor = (name: string | undefined) => (name ? <UserContent>{name}</UserContent> : a.system);

  const columns: Column<AuditEntry>[] = [
    { key: 'time', header: a.columns.time, cell: (entry) => <AuditTime entry={entry} locale={locale} /> },
    {
      key: 'author',
      header: a.columns.author,
      cell: (entry) =>
        entry.actorMemberId ? (
          <button
            type="button"
            onClick={() => setFilter('actorMemberId', entry.actorMemberId)}
            className="text-left underline decoration-dotted hover:text-accent"
          >
            {actor(entry.actorName)}
          </button>
        ) : (
          actor(entry.actorName)
        ),
    },
    { key: 'action', header: a.columns.action, cell: (entry) => a.action[entry.action] },
    { key: 'record', header: a.columns.record, cell: (entry) => <AuditRecord entry={entry} /> },
    { key: 'changes', header: a.columns.changes, cell: (entry) => <AuditChanges entry={entry} /> },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={a.title} />
      <Panel>
        <div className="flex flex-wrap items-start gap-4">
          <Select
            label={a.filters.entityType}
            value={entityType ?? ''}
            onChange={(e) => setFilter('entityType', e.target.value)}
          >
            <option value="">{a.filters.all}</option>
            {ENTITY_TYPES.map((type) => (
              <option key={type} value={type}>
                {a.entityType[type]}
              </option>
            ))}
          </Select>
          <TextField
            label={a.filters.from}
            error={dateError}
            type="date"
            value={fromParam}
            onChange={(e) => setFilter('from', e.target.value)}
          />
          <TextField
            label={a.filters.to}
            type="date"
            value={toParam}
            onChange={(e) => setFilter('to', e.target.value)}
          />
        </div>
        {(entityId ?? actorMemberId) && (
          <div className="flex flex-wrap gap-2">
            {entityId && (
              <Chip label={a.filters.onlyRecord} onRemove={() => setFilter('entityId', undefined)} />
            )}
            {actorMemberId && (
              <Chip label={a.filters.onlyAuthor} onRemove={() => setFilter('actorMemberId', undefined)} />
            )}
          </div>
        )}
        <QueryBoundary
          query={query}
          isEmpty={(data) => data.items.length === 0}
          emptyMessage={filtered ? a.nothingFound : a.empty}
        >
          {(data) => (
            <>
              <DataTable
                columns={columns}
                rows={data.items}
                rowKey={(entry) => entry.id}
                caption={a.title}
                emptyMessage={a.empty}
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

function Chip({ label, onRemove }: { label: string; onRemove: () => void }) {
  const t = useT();
  return (
    <span className="flex items-center gap-2 rounded-full border border-line bg-accent-soft px-3 py-1 text-sm text-ink">
      {label}
      <button
        type="button"
        onClick={onRemove}
        aria-label={`${t.audit.filters.remove}: ${label}`}
        className="text-ink-muted hover:text-ink"
      >
        ×
      </button>
    </span>
  );
}

function AuditTime({ entry, locale }: { entry: AuditEntry; locale: string }) {
  return (
    <time dateTime={entry.occurredAt} className="whitespace-nowrap">
      {formatDateTime(entry.occurredAt, locale)}
    </time>
  );
}

function AuditRecord({ entry }: { entry: AuditEntry }) {
  const t = useT();
  const { locale } = useLocale();
  const a = t.audit;
  const label = entry.entityLabel
    ? auditRecordLabel(entry.entityType, entry.entityLabel, t, locale)
    : a.unlabelled;
  const href = entityHref[entry.entityType]?.(entry.entityId);
  return (
    <>
      <span className="text-ink-muted">{a.entityType[entry.entityType]}: </span>
      {href ? (
        <Link to={href} className="text-accent underline">
          {label}
        </Link>
      ) : (
        label
      )}
    </>
  );
}

function AuditChanges({ entry }: { entry: AuditEntry }) {
  const t = useT();
  const { locale } = useLocale();
  const a = t.audit;
  const fieldNames: Readonly<Record<string, string>> = { ...t.member.fields, ...a.fields };
  return (
    <ul className="flex flex-col gap-1">
      {entry.changes.map((change) => (
        <li key={change.field}>
          <span className="text-ink-muted">
            {(entry.entityType === 'METER' && change.field === 'effectiveFrom'
              ? a.fields.meterEffectiveFrom
              : fieldNames[change.field]) ?? change.field}
            :{' '}
          </span>
          {auditValue(entry.entityType, change.field, change.oldValue, t, locale)} →{' '}
          {auditValue(entry.entityType, change.field, change.newValue, t, locale)}
        </li>
      ))}
    </ul>
  );
}
