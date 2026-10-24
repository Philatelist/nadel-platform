import { useListChargeTypes, useRunRecalculation, type RecalculationCause } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { invalidateAfterRecalculation } from '../components/charges/chargeInvalidation';
import { RecalculationStep, type RecalculationRunner } from '../components/recalculation/RecalculationStep';
import { PlotPicker, type PickedPlot } from '../components/tariffs/PlotPicker';
import { UserContent } from '../components/UserContent';
import { Button, MonthField, PageHeader, Panel, RadioGroup } from '../components/ui';
import { useT } from '../i18n';
import { kyivToday } from '../kyivToday';
import { useDocumentTitle } from '../useDocumentTitle';

type Choice = 'all' | 'chosen';

/** `/charges/recalculations/new`: pick the scope, look at the preview, book it. Needs RECALCULATION_RUN. */
export function RecalculationNewPage() {
  const t = useT();
  const f = t.recalculation.form;
  useDocumentTitle(f.title);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const month = kyivToday().slice(0, 7);
  const types = useListChargeTypes();
  const mutation = useRunRecalculation();
  const [from, setFrom] = useState(month);
  const [to, setTo] = useState(month);
  const [typeChoice, setTypeChoice] = useState<Choice>('all');
  const [typeIds, setTypeIds] = useState<string[]>([]);
  const [plotChoice, setPlotChoice] = useState<Choice>('all');
  const [plots, setPlots] = useState<PickedPlot[]>([]);
  const [shown, setShown] = useState(false);
  const [cause, setCause] = useState<RecalculationCause>();

  const monthsError =
    from === '' || to === ''
      ? f.monthsRequired
      : from > to
        ? f.monthsOrder
        : to > month
          ? f.monthInFuture
          : undefined;
  const typesError = typeChoice === 'chosen' && typeIds.length === 0 ? f.chargeTypesNone : undefined;
  const plotsError = plotChoice === 'chosen' && plots.length === 0 ? f.plotsNone : undefined;
  const invalid = monthsError !== undefined || typesError !== undefined || plotsError !== undefined;

  function preview() {
    setShown(true);
    if (invalid) return;
    setCause({
      type: 'MANUAL',
      manual: {
        from,
        to,
        ...(typeChoice === 'chosen' ? { chargeTypeIds: typeIds } : {}),
        ...(plotChoice === 'chosen' ? { plotIds: plots.map((p) => p.id) } : {}),
      },
    });
  }

  const run: RecalculationRunner = (mode, digest, reason) =>
    mutation.mutateAsync({
      data: {
        cause: cause as RecalculationCause,
        ...(reason !== undefined ? { reason } : {}),
        ...(digest ? { digest } : {}),
      },
      params: { preview: mode === 'preview' },
    });

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={f.title} description={f.description} />
      <ChargesTabs />
      <Link to="/charges/recalculations" className="w-fit text-accent underline">
        {f.back}
      </Link>
      {cause ? (
        <RecalculationStep
          run={run}
          onBack={() => setCause(undefined)}
          onBooked={async (booked) => {
            await invalidateAfterRecalculation(queryClient);
            if (booked.id) await navigate(`/charges/recalculations/${booked.id}`);
          }}
        />
      ) : (
        <Panel>
          <form
            noValidate
            className="flex flex-col gap-5"
            onSubmit={(event) => {
              event.preventDefault();
              preview();
            }}
          >
            <div className="flex flex-wrap items-start gap-4">
              <MonthField label={f.from} value={from} max={month} onChange={setFrom} />
              <MonthField
                label={f.to}
                value={to}
                max={month}
                error={shown ? monthsError : undefined}
                onChange={setTo}
              />
            </div>
            <RadioGroup
              legend={f.chargeTypes}
              value={typeChoice}
              onChange={setTypeChoice}
              options={[
                { value: 'all', label: f.chargeTypesAll },
                { value: 'chosen', label: f.chargeTypesChosen },
              ]}
              error={shown ? typesError : undefined}
            />
            {typeChoice === 'chosen' && (
              <ul className="flex flex-col gap-1">
                {(types.data ?? [])
                  .filter((type) => type.active)
                  .map((type) => (
                    <li key={type.id}>
                      <label className="flex min-h-11 items-center gap-3">
                        <input
                          type="checkbox"
                          className="size-4 accent-accent"
                          checked={typeIds.includes(type.id)}
                          onChange={(e) =>
                            setTypeIds(
                              e.target.checked
                                ? [...typeIds, type.id]
                                : typeIds.filter((id) => id !== type.id),
                            )
                          }
                        />
                        <ChargeTypeName type={type} />
                      </label>
                    </li>
                  ))}
              </ul>
            )}
            <RadioGroup
              legend={f.plots}
              value={plotChoice}
              onChange={setPlotChoice}
              options={[
                { value: 'all', label: f.plotsAll },
                { value: 'chosen', label: f.plotsChosen },
              ]}
              error={shown ? plotsError : undefined}
            />
            {plotChoice === 'chosen' && (
              <div className="flex flex-col gap-2">
                <PlotPicker
                  label={f.plotsSearch}
                  excludeIds={plots.map((p) => p.id)}
                  onPick={(plot) => setPlots([...plots, plot])}
                />
                <ul className="flex flex-wrap gap-2">
                  {plots.map((plot) => (
                    <li key={plot.id}>
                      <button
                        type="button"
                        aria-label={format(f.removePlot, { number: plot.number })}
                        onClick={() => setPlots(plots.filter((p) => p.id !== plot.id))}
                        className="inline-flex min-h-11 items-center gap-2 rounded-control border border-line-strong px-3 hover:bg-accent-soft"
                      >
                        <UserContent>{plot.number}</UserContent>
                        <span aria-hidden="true">×</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div>
              <Button type="submit">{f.preview}</Button>
            </div>
          </form>
        </Panel>
      )}
    </div>
  );
}
