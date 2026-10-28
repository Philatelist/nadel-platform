import {
  MeterKind,
  MeterResource,
  useListMeters,
  type Meter,
  type MeterKind as MeterKindValue,
  type MeterResource as MeterResourceValue,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue } from '@nadel/shared';
import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { RegisterMeterDialog } from '../components/meters/RegisterMeterDialog';
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
  type Column,
} from '../components/ui';
import { useCanManageMeters } from '../hooks/useCanManageMeters';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useLocale, useT } from '../i18n';
import { meterLabel, resourceUnit } from '../meters';
import { useDocumentTitle } from '../useDocumentTitle';
import { ResourceIcon } from '../components/meters/ResourceIcon';

const KINDS = Object.values(MeterKind);
const RESOURCES = Object.values(MeterResource);
const SEARCH_DELAY_MS = 300;

function oneOf<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((v) => v === value);
}

export function MetersPage() {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  useDocumentTitle(m.title);
  const canManage = useCanManageMeters();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const resource: MeterResourceValue | undefined = oneOf(RESOURCES, params.get('resource'));
  const kind: MeterKindValue | undefined = oneOf(KINDS, params.get('kind'));
  const street = params.get('street') ?? '';
  const includeRemoved = params.get('removed') === 'true';
  const [registering, setRegistering] = useState(false);

  const [input, setInput] = useState(street);

  // A changed search resets paging where there is one; the URL keeps the filters shareable.
  useDebouncedSearchParam('street', input, SEARCH_DELAY_MS);

  function update(change: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next);
  }

  const query = useListMeters({
    resource,
    kind,
    street: street || undefined,
    includeRemoved: includeRemoved || undefined,
  });
  const filtered = resource !== undefined || kind !== undefined || street !== '' || includeRemoved;

  const c = m.columns;
  const columns: Column<Meter>[] = [
    {
      key: 'plot',
      header: c.plot,
      cell: (meter) => (meter.plot ? <UserContent>{meter.plot.number}</UserContent> : m.fields.none),
    },
    {
      key: 'name',
      header: c.name,
      cell: (meter) => (
        <Link to={`/meters/${meter.id}`} className="font-medium text-accent underline">
          <UserContent>{meterLabel(meter)}</UserContent>
        </Link>
      ),
    },
    {
      key: 'serial',
      header: c.serial,
      cell: (meter) => <UserContent>{meter.serial}</UserContent>,
    },
    { key: 'kind', header: c.kind, cell: (meter) => m.kind[meter.kind] },
    {
      key: 'resource',
      header: c.resource,
      cell: (meter) => (
        <span className="inline-flex items-center gap-1.5">
          <ResourceIcon resource={meter.resource} />
          {m.resource[meter.resource]}
        </span>
      ),
    },
    {
      key: 'lastReading',
      header: c.lastReading,
      cell: (meter) =>
        format(m.lastReadingValue, {
          value: formatMeterValue(meter.lastReading.value, locale),
          unit: resourceUnit(t, meter.resource),
          date: formatDay(meter.lastReading.takenOn, locale),
        }),
    },
    {
      key: 'status',
      header: c.status,
      cell: (meter) => (
        <Badge tone={meter.active ? 'success' : 'neutral'}>{meter.active ? m.active : m.removed}</Badge>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={m.title}
        actions={canManage && <Button onClick={() => setRegistering(true)}>{m.add}</Button>}
      />
      <Panel>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <Select
            label={m.filterResource}
            value={resource ?? ''}
            onChange={(e) =>
              update((next) => {
                if (e.target.value) next.set('resource', e.target.value);
                else next.delete('resource');
              })
            }
          >
            <option value="">{m.allResources}</option>
            {RESOURCES.map((r) => (
              <option key={r} value={r}>
                {m.resource[r]}
              </option>
            ))}
          </Select>
          <Select
            label={m.filterKind}
            value={kind ?? ''}
            onChange={(e) =>
              update((next) => {
                if (e.target.value) next.set('kind', e.target.value);
                else next.delete('kind');
              })
            }
          >
            <option value="">{m.allKinds}</option>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {m.kind[k]}
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
          <label className="flex min-h-11 items-center gap-2">
            <input
              type="checkbox"
              checked={includeRemoved}
              onChange={(e) =>
                update((next) => {
                  if (e.target.checked) next.set('removed', 'true');
                  else next.delete('removed');
                })
              }
            />
            {m.showRemoved}
          </label>
        </div>
        <QueryBoundary
          query={query}
          isEmpty={(data) => data.length === 0}
          emptyMessage={filtered ? m.nothingFound : m.empty}
        >
          {(data) => (
            <DataTable
              columns={columns}
              rows={data}
              rowKey={(meter) => meter.id}
              caption={m.title}
              emptyMessage={m.empty}
            />
          )}
        </QueryBoundary>
      </Panel>
      {registering && (
        <RegisterMeterDialog
          onClose={() => setRegistering(false)}
          onRegistered={(meter) => {
            setRegistering(false);
            void navigate(`/meters/${meter.id}`);
          }}
        />
      )}
    </div>
  );
}
