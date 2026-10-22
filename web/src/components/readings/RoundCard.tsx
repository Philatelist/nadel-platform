import { Link } from 'react-router';
import { useT } from '../../i18n';
import { meterLabel } from '../../meters';
import type { RoundGroup } from '../../readingsRound';
import { UserContent, formatUser } from '../UserContent';
import { RoundRow } from './RoundRow';

/** One card of the round: a plot with a row per meter, or a single non-plot meter. Each row saves on its own. */
export function RoundCard({
  group,
  roundDate,
  onSaved,
}: {
  group: RoundGroup;
  roundDate: string | undefined;
  onSaved: (field: HTMLInputElement | null) => void;
}) {
  const r = useT().readings;
  const [first] = group.meters;
  return (
    <li className="min-w-0 rounded-panel border border-line bg-surface p-4">
      <div className="mb-3 flex flex-wrap items-center gap-x-2 gap-y-1 font-semibold break-words">
        {group.plot ? (
          <>
            <span>{formatUser(r.plotNumber, { number: group.plot.number })}</span>
            {group.plot.street && (
              <span className="font-normal text-ink-muted">
                <UserContent>{group.plot.street}</UserContent>
              </span>
            )}
          </>
        ) : (
          first && (
            <Link to={`/meters/${first.id}`} className="text-accent underline">
              <UserContent>{meterLabel(first)}</UserContent>
            </Link>
          )
        )}
      </div>
      <div className="flex flex-col divide-y divide-line">
        {group.meters.map((meter) => (
          <div key={meter.id} className="py-3 first:pt-0 last:pb-0">
            <RoundRow meter={meter} roundDate={roundDate} onSaved={onSaved} />
          </div>
        ))}
      </div>
    </li>
  );
}
