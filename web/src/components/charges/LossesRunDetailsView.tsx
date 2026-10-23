import { useGetLossesRun } from '@nadel/api-client';
import { useLocale, useT } from '../../i18n';
import { Link } from 'react-router';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, DataTable, Money, type Column } from '../ui';
import { formatKwh } from './lossesFormat';
import { LossesNodesTable, LossesWarnings } from './LossesNodes';
import type { LossesRunCharge } from '@nadel/api-client';

/** The nodes, warnings and charges of one booked run. */
export function LossesRunDetailsView({ id }: { id: string }) {
  const t = useT();
  const { locale } = useLocale();
  const h = t.billing.runHistory;
  const query = useGetLossesRun(id);
  const columns: Column<LossesRunCharge>[] = [
    {
      key: 'plot',
      header: h.columns.plot,
      cell: (c) => (
        <Link to={`/plots/${c.plotId}`} className="text-accent underline">
          <UserContent>{c.plotNumber ?? c.plotId}</UserContent>
        </Link>
      ),
    },
    { key: 'kwh', header: h.columns.kwh, numeric: true, cell: (c) => formatKwh(c.kwh, locale) },
    { key: 'amount', header: h.columns.amount, numeric: true, cell: (c) => <Money amount={c.amount} /> },
    {
      key: 'state',
      header: h.columns.state,
      cell: (c) => (c.reversed ? <Badge tone="neutral">{h.reversedCharge}</Badge> : null),
    },
  ];
  return (
    <QueryBoundary query={query}>
      {(run) => (
        <div className="flex flex-col gap-4">
          <LossesWarnings nodes={run.nodes} />
          <section className="flex flex-col gap-2">
            <h3 className="font-semibold">{h.nodesTitle}</h3>
            <LossesNodesTable nodes={run.nodes} />
          </section>
          <section className="flex flex-col gap-2">
            <h3 className="font-semibold">{h.chargesTitle}</h3>
            <DataTable
              columns={columns}
              rows={run.charges}
              rowKey={(c) => c.chargeId ?? c.plotId}
              caption={h.chargesCaption}
              emptyMessage={h.empty}
            />
          </section>
        </div>
      )}
    </QueryBoundary>
  );
}
