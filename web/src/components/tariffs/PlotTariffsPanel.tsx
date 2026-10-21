import { ApiError, useGetPlotTariffs } from '@nadel/api-client';
import { useState } from 'react';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { QueryBoundary } from '../QueryBoundary';
import { MonthField, Panel } from '../ui';
import { TariffAmountRows } from './TariffAmountRows';

/** The tariffs of a plot for a chosen month (default: the current Kyiv month); renders nothing on 404. */
export function PlotTariffsPanel({ plotId }: { plotId: string }) {
  const t = useT();
  const [month, setMonth] = useState(() => kyivToday().slice(0, 7));
  const tariffs = useGetPlotTariffs(plotId, { month });
  if (tariffs.error instanceof ApiError && tariffs.error.status === 404) return null;
  return (
    <Panel title={t.tariffs.expected.panelTitle}>
      <div className="max-w-xs">
        <MonthField
          label={t.tariffs.expected.month}
          value={month}
          onChange={(next) => next && setMonth(next)}
        />
      </div>
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
