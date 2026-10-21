import { ApiError, useGetPlotTariffs, type OwnedPlot } from '@nadel/api-client';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useMe } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { formatUser } from '../UserContent';
import { Panel } from '../ui';
import { TariffAmountRows } from './TariffAmountRows';

function MyPlotCharges({ plot, month }: { plot: OwnedPlot; month: string }) {
  const t = useT();
  const tariffs = useGetPlotTariffs(plot.plotId, { month });
  if (tariffs.error instanceof ApiError && tariffs.error.status === 404) return null;
  return (
    <Panel title={formatUser(t.plot.cardTitlePlot, { number: plot.number })}>
      <QueryBoundary
        query={tariffs}
        isEmpty={(data) => data.items.length === 0}
        emptyMessage={t.tariffs.expected.empty}
      >
        {(data) => <TariffAmountRows data={data} />}
      </QueryBoundary>
    </Panel>
  );
}

/** "What I will be charged" on the account page: the current month's expected amounts per owned plot. */
export function MyChargesPanel() {
  const t = useT();
  const { ownedPlots } = useMe();
  if (ownedPlots.length === 0) return null;
  const month = kyivToday().slice(0, 7);
  return (
    <section aria-labelledby="my-charges" className="flex flex-col gap-3">
      <h2 id="my-charges" className="font-serif text-lg font-semibold text-ink">
        {t.tariffs.expected.myTitle}
      </h2>
      {ownedPlots.map((p) => (
        <MyPlotCharges key={p.plotId} plot={p} month={month} />
      ))}
    </section>
  );
}
