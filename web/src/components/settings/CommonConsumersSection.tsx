import {
  getGetCommonConsumerBillingQueryKey,
  getGetSettingsQueryKey,
  listPlots,
  useGetCommonConsumerBilling,
  useListMeters,
  useUpdateCommonConsumerBilling,
  useUpdateSettings,
  type CommonConsumerBilling,
  type CommonConsumerSplit,
  type CooperativeSettings,
  type Meter,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { problemFieldErrors, problemMessage } from '../../problem';
import { QueryBoundary } from '../QueryBoundary';
import { PlotPicker, type PickedPlot } from '../tariffs/PlotPicker';
import { UserContent } from '../UserContent';
import { Button, Notice, Panel, RadioGroup, Select } from '../ui';

const SPLITS = [
  'CONNECTED_PLOTS',
  'EQUAL_PER_PLOT',
  'SERVICE_USERS',
  'NOT_CHARGED',
] as const satisfies readonly CommonConsumerSplit[];

const PAGE_SIZE = 100;

/** Plot id to number for every plot of the cooperative, archived ones included; the billing carries ids only. */
function usePlotNumbers(enabled: boolean) {
  return useQuery({
    queryKey: ['plot-numbers'],
    enabled,
    staleTime: 60_000,
    queryFn: async (): Promise<ReadonlyMap<string, string>> => {
      const numbers = new Map<string, string>();
      for (let page = 0; ; page += 1) {
        const result = await listPlots({ kind: 'PLOT', includeArchived: true, page, size: PAGE_SIZE });
        for (const plot of result.items) numbers.set(plot.id, plot.number);
        if (page + 1 >= result.totalPages) return numbers;
      }
    },
  });
}

/**
 * How common consumers are shared among plots (SETTINGS_MANAGE): the cooperative default and, per consumer, its own
 * split and its service users. The server checks the plots; its errors are shown at the plot they are about.
 */
export function CommonConsumersSection({ saved }: { saved: CooperativeSettings }) {
  const t = useT();
  const s = t.settings.commonConsumers;
  const canMeters = useCan('METERS_VIEW');
  const meters = useListMeters({ kind: 'COMMON_CONSUMER' }, { query: { enabled: canMeters } });
  return (
    <Panel title={s.title}>
      <div className="flex max-w-xl flex-col gap-6">
        <p className="text-sm text-ink-muted">{s.text}</p>
        <DefaultSplitForm saved={saved.commonConsumerDefaultSplit} />
        {canMeters && (
          <QueryBoundary query={meters} isEmpty={(data) => data.length === 0} emptyMessage={s.noConsumers}>
            {(data) => (
              <ul className="flex flex-col gap-6">
                {data.map((meter) => (
                  <ConsumerItem
                    key={meter.id}
                    meter={meter}
                    defaultSplit={saved.commonConsumerDefaultSplit}
                  />
                ))}
              </ul>
            )}
          </QueryBoundary>
        )}
      </div>
    </Panel>
  );
}

function DefaultSplitForm({ saved }: { saved: CommonConsumerSplit }) {
  const t = useT();
  const s = t.settings.commonConsumers;
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<CommonConsumerSplit>(saved);
  const [result, setResult] = useState<'saved' | { error: string }>();
  const update = useUpdateSettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetSettingsQueryKey(), data);
        setChoice(data.commonConsumerDefaultSplit);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });
  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        setResult(undefined);
        update.mutate({ data: { commonConsumerDefaultSplit: choice } });
      }}
    >
      <RadioGroup
        legend={s.defaultLegend}
        value={choice}
        onChange={(value) => {
          setChoice(value);
          setResult(undefined);
        }}
        options={SPLITS.map((value) => ({ value, label: t.billing.common.split[value] }))}
      />
      {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
      {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
      <div>
        <Button type="submit" disabled={choice === saved || update.isPending}>
          {s.saveDefault}
        </Button>
      </div>
    </form>
  );
}

function ConsumerItem({ meter, defaultSplit }: { meter: Meter; defaultSplit: CommonConsumerSplit }) {
  const query = useGetCommonConsumerBilling(meter.id);
  return (
    <li className="flex flex-col gap-2 border-t border-line-subtle pt-4">
      <h3 className="font-medium">{meter.name ? <UserContent>{meter.name}</UserContent> : meter.serial}</h3>
      <QueryBoundary query={query}>
        {(billing) => <ConsumerBillingForm billing={billing} defaultSplit={defaultSplit} />}
      </QueryBoundary>
    </li>
  );
}

/** `billing` is the server value; the form starts from it, so it is mounted once per consumer. */
function ConsumerBillingForm({
  billing,
  defaultSplit,
}: {
  billing: CommonConsumerBilling;
  defaultSplit: CommonConsumerSplit;
}) {
  const t = useT();
  const s = t.settings.commonConsumers;
  const queryClient = useQueryClient();
  const [split, setSplit] = useState<CommonConsumerSplit | ''>(billing.split ?? '');
  const [plotIds, setPlotIds] = useState<string[]>(billing.servicePlotIds);
  const [picked, setPicked] = useState<ReadonlyMap<string, string>>(new Map());
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState(false);
  const numbers = usePlotNumbers(plotIds.some((id) => !picked.has(id)));

  const effective = split === '' ? defaultSplit : split;
  const changed =
    split !== (billing.split ?? '') ||
    plotIds.length !== billing.servicePlotIds.length ||
    plotIds.some((id, index) => id !== billing.servicePlotIds[index]);

  const update = useUpdateCommonConsumerBilling({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetCommonConsumerBillingQueryKey(billing.meterId), data);
        setSplit(data.split ?? '');
        setPlotIds(data.servicePlotIds);
        setError(undefined);
        setDone(true);
      },
      onError: (e: unknown) => {
        setDone(false);
        setError(e);
      },
    },
  });

  const fieldErrors = problemFieldErrors(t, error);
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;
  const numberOf = (id: string) => picked.get(id) ?? numbers.data?.get(id) ?? '…';

  function pick(plot: PickedPlot) {
    setPicked((current) => new Map(current).set(plot.id, plot.number));
    setPlotIds((current) => [...current, plot.id]);
    touch();
  }
  function touch() {
    setDone(false);
    setError(undefined);
  }

  return (
    <form
      className="flex flex-col gap-3"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        setDone(false);
        update.mutate({
          meterId: billing.meterId,
          data: { ...(split === '' ? {} : { split }), servicePlotIds: plotIds },
        });
      }}
    >
      <Select
        label={s.consumerSplit}
        value={split}
        onChange={(e) => {
          setSplit(e.target.value === '' ? '' : (e.target.value as CommonConsumerSplit));
          touch();
        }}
      >
        <option value="">{format(s.byDefault, { split: t.billing.common.split[defaultSplit] })}</option>
        {SPLITS.map((value) => (
          <option key={value} value={value}>
            {t.billing.common.split[value]}
          </option>
        ))}
      </Select>
      {effective === 'SERVICE_USERS' && (
        <div className="flex flex-col gap-2">
          <h4 className="text-sm font-medium text-ink-muted">{s.serviceUsers}</h4>
          <p className="text-sm text-ink-muted">{s.serviceUsersHint}</p>
          {plotIds.length === 0 ? (
            <p className="text-sm text-ink-muted">{s.noServiceUsers}</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {plotIds.map((id, index) => (
                <li key={id} className="flex flex-wrap items-center gap-2">
                  <UserContent>{numberOf(id)}</UserContent>
                  <Button
                    variant="secondary"
                    className="min-h-8 px-2 text-sm"
                    aria-label={format(s.removePlot, { plot: numberOf(id) })}
                    onClick={() => {
                      setPlotIds((current) => current.filter((other) => other !== id));
                      touch();
                    }}
                  >
                    ×
                  </Button>
                  {fieldErrors[`servicePlotIds[${index}]`] && (
                    <span role="alert" className="text-sm text-danger">
                      {fieldErrors[`servicePlotIds[${index}]`]}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <PlotPicker label={s.pickPlot} excludeIds={plotIds} onPick={pick} />
        </div>
      )}
      {error !== undefined && !hasFieldErrors && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
      {done && <Notice tone="success">{s.saved}</Notice>}
      <div>
        <Button type="submit" disabled={!changed || update.isPending}>
          {update.isPending ? s.saving : s.save}
        </Button>
      </div>
    </form>
  );
}
