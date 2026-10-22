import {
  useGetMeterTree,
  type MeterRangeConsumption,
  type MeterTreeMeter,
  type MeterTreeNode,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue, roundMeterValue } from '@nadel/shared';
import { ResourceIcon } from '../components/meters/ResourceIcon';
import { useState } from 'react';
import { Link } from 'react-router';
import { MoveMeterDialog } from '../components/meterTree/MoveMeterDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent, formatUser } from '../components/UserContent';
import { Badge, Button, EmptyState, Notice, PageHeader, Panel, TextField } from '../components/ui';
import { useCanManageMeters } from '../hooks/useCanManageMeters';
import { useLocale, useT } from '../i18n';
import { kyivToday } from '../kyivToday';
import { meterLabel } from '../meters';
import { useDocumentTitle } from '../useDocumentTitle';

export function MeterTreePage() {
  const t = useT();
  const m = t.meterTree;
  useDocumentTitle(m.title);
  const today = kyivToday();
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [moving, setMoving] = useState<{ meter: MeterTreeMeter; parentId: string | null }>();
  const rangeValid = from !== '' && to !== '' && to >= from;
  const query = useGetMeterTree({ from, to }, { query: { enabled: rangeValid } });

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/settings" className="text-ink-muted hover:text-ink">
        {m.back}
      </Link>
      <PageHeader title={m.title} />
      <div className="flex flex-wrap items-start gap-4">
        <TextField
          label={m.from}
          type="date"
          value={from}
          max={to || undefined}
          onChange={(e) => setFrom(e.target.value)}
        />
        <TextField
          label={m.to}
          type="date"
          value={to}
          min={from || undefined}
          onChange={(e) => setTo(e.target.value)}
        />
      </div>
      {!rangeValid && from !== '' && to !== '' && <Notice tone="danger">{m.rangeInvalid}</Notice>}
      {rangeValid && (
        <QueryBoundary query={query}>
          {(tree) =>
            tree.roots.length === 0 ? (
              <EmptyState message={m.empty} />
            ) : (
              <Panel>
                <div className="overflow-x-auto">
                  <NodeList
                    nodes={tree.roots}
                    rangeFrom={tree.from}
                    rangeTo={tree.to}
                    parentId={null}
                    onMove={(meter, parentId) => setMoving({ meter, parentId })}
                  />
                </div>
              </Panel>
            )
          }
        </QueryBoundary>
      )}
      {moving && (
        <MoveMeterDialog
          key={moving.meter.id}
          meter={moving.meter}
          currentParentId={moving.parentId}
          onClose={() => setMoving(undefined)}
        />
      )}
    </div>
  );
}

interface NodeContext {
  rangeFrom: string;
  rangeTo: string;
  parentId: string | null;
  onMove: (meter: MeterTreeMeter, parentId: string | null) => void;
}

function NodeList({
  nodes,
  rangeFrom,
  rangeTo,
  parentId,
  onMove,
}: NodeContext & { nodes: readonly MeterTreeNode[] }) {
  const t = useT();
  return (
    <ul aria-label={t.meterTree.listLabel} className="flex min-w-max flex-col gap-2">
      {nodes.map((node, index) => (
        <NodeItem
          key={`${node.meter.id}-${node.slices[0]?.from ?? index}`}
          node={node}
          rangeFrom={rangeFrom}
          rangeTo={rangeTo}
          parentId={parentId}
          onMove={onMove}
        />
      ))}
    </ul>
  );
}

function NodeItem({ node, rangeFrom, rangeTo, parentId, onMove }: NodeContext & { node: MeterTreeNode }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meterTree;
  const canManage = useCanManageMeters();
  const { meter } = node;
  // A meter shown under several parents is current only where its latest slice ends at the range end.
  const lastSlice = node.slices[node.slices.length - 1];
  const isCurrent = lastSlice === undefined || lastSlice.to === rangeTo;
  const partial = node.slices.length > 1 || node.slices.some((s) => s.from !== rangeFrom || s.to !== rangeTo);

  return (
    <li className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-control border border-line px-3 py-2">
        <span className="font-semibold">
          {/* the tree holds electricity meters only (water is not part of it) */}
          <ResourceIcon resource="ELECTRICITY" className="mr-1.5" />
          <UserContent>{meterLabel(meter)}</UserContent>
        </span>
        <Badge tone="neutral">{t.meters.kind[meter.kind]}</Badge>
        {meter.plot && (
          <span className="text-ink-muted">
            {formatUser(t.readings.plotNumber, { number: meter.plot.number })}
          </span>
        )}
        {meter.removedOn && (
          <span className="text-ink-muted">
            {format(m.removedOn, { date: formatDay(meter.removedOn, locale) })}
          </span>
        )}
        <span className="flex flex-wrap items-center gap-2">
          <Consumption value={node.total} />
        </span>
        {canManage && meter.active && isCurrent && (
          <Button variant="secondary" onClick={() => onMove(meter, parentId)}>
            {m.move}
          </Button>
        )}
      </div>
      {partial && (
        <ul className="ml-3 flex flex-col gap-1 text-sm text-ink-muted">
          {node.slices.map((slice) => (
            <li key={`${slice.from}-${slice.to}`} className="flex flex-wrap items-center gap-2">
              <span>
                {formatDay(slice.from, locale)} – {formatDay(slice.to, locale)}:
              </span>
              <Consumption value={slice.consumption} />
            </li>
          ))}
        </ul>
      )}
      {node.children.length > 0 && (
        <div className="ml-6 border-l border-line pl-3">
          <NodeList
            nodes={node.children}
            rangeFrom={rangeFrom}
            rangeTo={rangeTo}
            parentId={meter.id}
            onMove={onMove}
          />
        </div>
      )}
    </li>
  );
}

/** A range consumption as the server calculated it: the value (marked when calculated) or why it is unknown. */
function Consumption({ value }: { value: MeterRangeConsumption }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meterTree;
  if (!value.known || value.consumption === undefined) {
    return (
      <span className="text-warning-ink">
        {value.lastReadingOn
          ? format(m.noReadingAfter, { date: formatDay(value.lastReadingOn, locale) })
          : m.noReading}
      </span>
    );
  }
  // a calculated (interpolated) value is shown to whole kWh; the server keeps the full precision
  const shown = value.calculated ? roundMeterValue(value.consumption, 0) : value.consumption;
  return (
    <>
      <span>{format(m.consumption, { value: formatMeterValue(shown, locale), unit: t.units.kwh })}</span>
      {value.calculated && <Badge tone="info">{m.calculated}</Badge>}
    </>
  );
}
