import { useListPlotOwnerships, type OwnedPlot } from '@nadel/api-client';
import { format, formatShare } from '@nadel/shared';
import { Link } from 'react-router';
import { useT } from '../../i18n';
import { useMe } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { Panel } from '../ui';
import { OwnershipLists } from './OwnershipLists';

function MyPlotCard({ plot }: { plot: OwnedPlot }) {
  const t = useT();
  const ownerships = useListPlotOwnerships(plot.plotId);
  return (
    <Panel>
      <h3 className="text-[17px] font-semibold">
        <Link to={`/plots/${plot.plotId}`} className="text-accent underline">
          {format(t.plot.cardTitlePlot, { number: plot.number })}
        </Link>
      </h3>
      <p>
        {plot.street && <span className="mr-3">{plot.street}</span>}
        <span>{format(t.ownership.myShare, { share: formatShare(plot.share) })}</span>
      </p>
      <QueryBoundary query={ownerships} isEmpty={() => false}>
        {(data) => <OwnershipLists ownerships={data} />}
      </QueryBoundary>
    </Panel>
  );
}

/** "My plots" on the account page: each owned plot with the co-owners and the history. */
export function MyPlotsPanel() {
  const t = useT();
  const { ownedPlots } = useMe();
  return (
    <section aria-labelledby="my-plots" className="flex flex-col gap-3">
      <h2 id="my-plots" className="font-serif text-lg font-semibold text-ink">
        {t.ownership.myPlots}
      </h2>
      {ownedPlots.length === 0 ? (
        <p className="text-ink-muted">{t.ownership.noOwnedPlots}</p>
      ) : (
        ownedPlots.map((p) => <MyPlotCard key={p.plotId} plot={p} />)
      )}
    </section>
  );
}
