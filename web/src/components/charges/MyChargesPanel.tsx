import { useT } from '../../i18n';
import { useMe } from '../../me';
import { formatUser } from '../UserContent';
import { PlotAccountPanel } from './PlotAccountPanel';

/** "My charges" on the account page: the read-only account of each plot the member owns. */
export function MyChargesPanel() {
  const t = useT();
  const { ownedPlots } = useMe();
  if (ownedPlots.length === 0) return null;
  return (
    <section aria-labelledby="my-charges" className="flex flex-col gap-3">
      <h2 id="my-charges" className="font-serif text-lg font-semibold text-ink">
        {t.charges.myTitle}
      </h2>
      {ownedPlots.map((p) => (
        <PlotAccountPanel
          key={p.plotId}
          plotId={p.plotId}
          readOnly
          title={formatUser(t.plot.cardTitlePlot, { number: p.number })}
        />
      ))}
    </section>
  );
}
