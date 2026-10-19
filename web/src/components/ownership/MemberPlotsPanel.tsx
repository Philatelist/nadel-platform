import { useListMemberOwnerships, type MemberOwnershipItem } from '@nadel/api-client';
import { format, formatDay, formatShare } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan, useMe } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { Panel } from '../ui';

function PlotRow({ item, past }: { item: MemberOwnershipItem; past: boolean }) {
  const t = useT();
  const { locale } = useLocale();
  const from = formatDay(item.validFrom, locale);
  const when =
    past && item.validTo !== undefined
      ? format(t.ownership.period, { from, to: formatDay(item.validTo, locale) })
      : format(t.ownership.since, { date: from });
  return (
    <li className="flex flex-wrap items-baseline gap-x-3 border-b border-line pb-2 last:border-b-0 last:pb-0">
      <Link to={`/plots/${item.plotId}`} className="font-medium text-accent underline">
        {format(t.plot.cardTitlePlot, { number: item.plotNumber })}
      </Link>
      {item.street && <span>{item.street}</span>}
      <span>{formatShare(item.share)}</span>
      <span className="text-ink-muted">{when}</span>
    </li>
  );
}

function Section({
  title,
  items,
  empty,
  past,
}: {
  title: string;
  items: MemberOwnershipItem[];
  empty: string;
  past: boolean;
}) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-[15px] font-semibold">{title}</h3>
      {items.length === 0 ? (
        <p className="text-ink-muted">{empty}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((i) => (
            <PlotRow key={i.ownershipId} item={i} past={past} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** Plots a member owns now and owned before. Shown to viewers of plots or members, and to the member themself. */
export function MemberPlotsPanel({ memberId }: { memberId: string }) {
  const t = useT();
  const me = useMe();
  const canViewPlots = useCan('PLOTS_VIEW');
  const canViewMembers = useCan('MEMBERS_VIEW');
  const allowed = canViewPlots || canViewMembers || me.memberId === memberId;
  const ownerships = useListMemberOwnerships(memberId, { query: { enabled: allowed } });
  if (!allowed) return null;
  return (
    <Panel title={t.ownership.plotsTitle}>
      <QueryBoundary query={ownerships} isEmpty={() => false}>
        {(data) => (
          <div className="flex flex-col gap-5">
            <Section
              title={t.ownership.ownsNow}
              items={data.current}
              empty={t.ownership.noPlotsOwned}
              past={false}
            />
            <Section
              title={t.ownership.ownedBefore}
              items={data.past}
              empty={t.ownership.noPlotsBefore}
              past
            />
          </div>
        )}
      </QueryBoundary>
    </Panel>
  );
}
