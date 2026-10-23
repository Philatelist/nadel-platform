import type { TariffConflict } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { Link } from 'react-router';
import { useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Notice } from '../ui';

/** A warning per overlapping tariff: which one, how many plots, and the first plot numbers (server data). */
export function TariffConflicts({ conflicts }: { conflicts: readonly TariffConflict[] }) {
  const t = useT();
  const c = t.tariffs.conflict;
  if (conflicts.length === 0) return null;
  return (
    <ul className="flex flex-col gap-2">
      {conflicts.map((conflict) => (
        <li key={conflict.otherTariffId}>
          <Notice tone="warning">
            <p>
              {c.overlaps}{' '}
              <Link to={`/tariffs/${conflict.otherTariffId}`} className="font-medium text-accent underline">
                <UserContent>{conflict.otherTariffName}</UserContent>
              </Link>
              . {format(c.text, { count: conflict.plotCount })}
            </p>
            <p className="mt-1">
              {c.plots}:{' '}
              {conflict.plots.map((plot, index) => (
                <span key={plot.plotId}>
                  {index > 0 && ', '}
                  <Link to={`/plots/${plot.plotId}`} className="text-accent underline">
                    <UserContent>{plot.number}</UserContent>
                  </Link>
                </span>
              ))}
              {conflict.plotCount > conflict.plots.length &&
                ` ${format(c.more, { count: conflict.plotCount - conflict.plots.length })}`}
            </p>
          </Notice>
        </li>
      ))}
    </ul>
  );
}
