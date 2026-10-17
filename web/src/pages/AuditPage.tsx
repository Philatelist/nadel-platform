import { useListAuditEntries, type AuditEntry } from '@nadel/api-client';
import { keepPreviousData } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { Field } from '../components/Field';
import { SelectInput, TextInput } from '../components/inputs';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { useLocale, useT } from '../i18n';
import { problemCode, problemFieldErrors } from '../problem';

const PAGE_SIZE = 50;
const TIME_ZONE = 'Europe/Kyiv';
const ENTITY_TYPES = ['MEMBER', 'ROLE'] as const;
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

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-8">
      <h1 className="font-display text-2xl font-bold">{a.title}</h1>
      <div className="flex flex-wrap items-start gap-4">
        <Field label={a.filters.entityType}>
          {({ id }) => (
            <SelectInput
              id={id}
              invalid={false}
              value={entityType ?? ''}
              onChange={(e) => setFilter('entityType', e.target.value)}
            >
              <option value="">{a.filters.all}</option>
              {ENTITY_TYPES.map((type) => (
                <option key={type} value={type}>
                  {a.entityType[type]}
                </option>
              ))}
            </SelectInput>
          )}
        </Field>
        <Field label={a.filters.from} error={dateError}>
          {(c) => (
            <TextInput
              {...c}
              type="date"
              value={fromParam}
              onChange={(e) => setFilter('from', e.target.value)}
            />
          )}
        </Field>
        <Field label={a.filters.to}>
          {(c) => (
            <TextInput {...c} type="date" value={toParam} onChange={(e) => setFilter('to', e.target.value)} />
          )}
        </Field>
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
            <div className="overflow-x-auto rounded-xl border border-line bg-surface">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line text-muted">
                  <tr>
                    {(['time', 'author', 'action', 'record', 'changes'] as const).map((col) => (
                      <th key={col} scope="col" className="px-3 py-2 font-medium">
                        {a.columns[col]}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {data.items.map((entry) => (
                    <AuditRow
                      key={entry.id}
                      entry={entry}
                      locale={locale}
                      onAuthor={(id) => setFilter('actorMemberId', id)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
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
    </div>
  );
}

function Chip({ label, onRemove }: { label: string; onRemove: () => void }) {
  const t = useT();
  return (
    <span className="flex items-center gap-2 rounded-full border border-line bg-accent-soft px-3 py-1 text-sm">
      {label}
      <button
        type="button"
        onClick={onRemove}
        aria-label={`${t.audit.filters.remove}: ${label}`}
        className="text-muted hover:text-ink"
      >
        ×
      </button>
    </span>
  );
}

function AuditRow({
  entry,
  locale,
  onAuthor,
}: {
  entry: AuditEntry;
  locale: string;
  onAuthor: (id: string) => void;
}) {
  const t = useT();
  const a = t.audit;
  const fieldNames: Readonly<Record<string, string>> = { ...t.member.fields, ...a.fields };
  const label = entry.entityLabel ?? a.unlabelled;
  const value = (field: string, raw: string | undefined): string => {
    if (raw === undefined || raw === '') return a.noValue;
    if (field === 'archived') return raw === 'true' ? a.yes : a.no;
    return raw;
  };

  return (
    <tr className="align-top">
      <td className="whitespace-nowrap px-3 py-2">
        <time dateTime={entry.occurredAt}>
          {new Intl.DateTimeFormat(locale, {
            dateStyle: 'short',
            timeStyle: 'medium',
            timeZone: TIME_ZONE,
          }).format(new Date(entry.occurredAt))}
        </time>
      </td>
      <td className="px-3 py-2">
        {entry.actorMemberId ? (
          <button
            type="button"
            onClick={() => onAuthor(entry.actorMemberId as string)}
            className="text-left underline decoration-dotted hover:text-accent"
          >
            {entry.actorName ?? a.system}
          </button>
        ) : (
          (entry.actorName ?? a.system)
        )}
      </td>
      <td className="px-3 py-2">{a.action[entry.action]}</td>
      <td className="px-3 py-2">
        <span className="text-muted">{a.entityType[entry.entityType]}: </span>
        {entry.entityType === 'MEMBER' ? (
          <Link to={`/members/${entry.entityId}`} className="underline hover:text-accent">
            {label}
          </Link>
        ) : (
          label
        )}
      </td>
      <td className="px-3 py-2">
        <ul className="flex flex-col gap-1">
          {entry.changes.map((change) => (
            <li key={change.field}>
              <span className="text-muted">{fieldNames[change.field] ?? change.field}: </span>
              {value(change.field, change.oldValue)} → {value(change.field, change.newValue)}
            </li>
          ))}
        </ul>
      </td>
    </tr>
  );
}
