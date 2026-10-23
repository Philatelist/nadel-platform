import {
  getGetSettingsQueryKey,
  useGetSettings,
  useUpdateSettings,
  type CooperativeSettings as Settings,
  type LossesMode,
  type PlotStatusVisibility,
} from '@nadel/api-client';
import { normalizeMoneyInput, normalizePercentInput } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { WaterSourcesSection } from '../components/water/WaterSourcesSection';
import { QueryBoundary } from '../components/QueryBoundary';
import { Button, Notice, PageHeader, Panel, RadioGroup, TextField } from '../components/ui';
import { useCanManageMeters } from '../hooks/useCanManageMeters';
import { useCan } from '../me';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const LOSSES_MODES = ['FIXED_PERCENT', 'PROPORTIONAL'] as const satisfies readonly LossesMode[];
const VISIBILITIES = ['OWN_ONLY', 'ALL_PLOTS'] as const satisfies readonly PlotStatusVisibility[];

export function SettingsPage() {
  const t = useT();
  useDocumentTitle(t.settings.title);
  const canSettings = useCan('SETTINGS_MANAGE');
  const canTree = useCanManageMeters();
  const canViewPlots = useCan('PLOTS_VIEW');
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={t.settings.title} />
      {canSettings && <CooperativeSettings />}
      {canViewPlots && <WaterSourcesSection />}
      {canTree && (
        <Panel>
          <Link to="/settings/meter-tree" className="text-[17px] font-semibold text-accent underline">
            {t.settings.meterTreeLink}
          </Link>
          <p className="text-ink-muted">{t.settings.meterTreeText}</p>
        </Panel>
      )}
    </div>
  );
}

function CooperativeSettings() {
  const query = useGetSettings();
  return (
    <QueryBoundary query={query}>
      {(data) => (
        <>
          <SettingsForm saved={data.memberPlotStatusVisibility} />
          <StatusThresholdSection saved={data} />
          <LossesSettingsSection saved={data} />
        </>
      )}
    </QueryBoundary>
  );
}

/** `saved` is the server value; the choice starts from it, so mount it once per loaded value. */
function SettingsForm({ saved }: { saved: PlotStatusVisibility }) {
  const t = useT();
  const s = t.settings;
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<PlotStatusVisibility>(saved);
  const [result, setResult] = useState<'saved' | { error: string }>();

  const update = useUpdateSettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetSettingsQueryKey(), data);
        setChoice(data.memberPlotStatusVisibility);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });

  return (
    <Panel>
      <form
        className="flex max-w-xl flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          setResult(undefined);
          update.mutate({ data: { memberPlotStatusVisibility: choice } });
        }}
      >
        <RadioGroup
          legend={s.visibilityLegend}
          value={choice}
          onChange={(value) => {
            setChoice(value);
            setResult(undefined);
          }}
          options={VISIBILITIES.map((value) => ({
            value,
            label: s.visibility[value].label,
            description: s.visibility[value].description,
          }))}
        />
        <p className="text-sm text-ink-muted">{s.visibilityHint}</p>
        {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
        {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
        <div>
          <Button type="submit" disabled={choice === saved || update.isPending}>
            {update.isPending ? s.saving : s.save}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

const DAYS_RE = /^\d{1,5}$/;

/** The red status threshold: the debt amount and the debt age. The server checks the ranges. */
function StatusThresholdSection({ saved }: { saved: Settings }) {
  const t = useT();
  const s = t.settings;
  const queryClient = useQueryClient();
  const [amount, setAmount] = useState(saved.redAmount);
  const [days, setDays] = useState(String(saved.redDays));
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<'saved' | { error: string }>();

  const update = useUpdateSettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetSettingsQueryKey(), data);
        setAmount(data.redAmount);
        setDays(String(data.redDays));
        setSubmitted(false);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });

  const normalizedAmount = normalizeMoneyInput(amount);
  const daysValid = DAYS_RE.test(days.trim());
  const changed = normalizedAmount !== saved.redAmount || days.trim() !== String(saved.redDays);

  return (
    <Panel title={s.threshold.title}>
      <form
        className="flex max-w-xl flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          setSubmitted(true);
          setResult(undefined);
          if (normalizedAmount === undefined || !daysValid) return;
          update.mutate({ data: { redAmount: normalizedAmount, redDays: Number(days.trim()) } });
        }}
      >
        <p className="text-sm text-ink-muted">{s.threshold.text}</p>
        <TextField
          label={s.threshold.redAmount}
          inputMode="decimal"
          value={amount}
          error={submitted && normalizedAmount === undefined ? s.threshold.amountInvalid : undefined}
          onChange={(e) => {
            setAmount(e.target.value);
            setResult(undefined);
          }}
        />
        <TextField
          label={s.threshold.redDays}
          inputMode="numeric"
          value={days}
          error={submitted && !daysValid ? s.threshold.daysInvalid : undefined}
          onChange={(e) => {
            setDays(e.target.value);
            setResult(undefined);
          }}
        />
        {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
        {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
        <div>
          <Button type="submit" disabled={!changed || update.isPending}>
            {s.threshold.save}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

/** A percentage as text without insignificant zeros ("5.00" and "5" both give "5"); no float arithmetic. */
function canonicalPercent(text: string): string {
  const [integer = '0', fraction = ''] = text.split('.');
  const digits = fraction.replace(/0+$/, '');
  const whole = integer.replace(/^0+(?=\d)/, '');
  return digits ? `${whole}.${digits}` : whole;
}

/** How the losses of a meter tree node are charged; the percentage applies to FIXED_PERCENT. The server checks the range. */
function LossesSettingsSection({ saved }: { saved: Settings }) {
  const t = useT();
  const s = t.settings;
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<LossesMode>(saved.lossesMode);
  const [percent, setPercent] = useState(saved.lossesPercent);
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<'saved' | { error: string }>();

  const update = useUpdateSettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetSettingsQueryKey(), data);
        setMode(data.lossesMode);
        setPercent(data.lossesPercent);
        setSubmitted(false);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });

  const normalized = normalizePercentInput(percent);
  // "5" and the saved "5.00" are the same percentage: compare them as normalised numeric text
  const changed =
    mode !== saved.lossesMode ||
    normalized === undefined ||
    canonicalPercent(normalized) !== canonicalPercent(saved.lossesPercent);

  return (
    <Panel title={s.losses.title}>
      <form
        className="flex max-w-xl flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          setSubmitted(true);
          setResult(undefined);
          if (normalized === undefined) return;
          update.mutate({ data: { lossesMode: mode, lossesPercent: normalized } });
        }}
      >
        <p className="text-sm text-ink-muted">{s.losses.text}</p>
        <RadioGroup
          legend={s.losses.mode}
          value={mode}
          onChange={(value) => {
            setMode(value);
            setResult(undefined);
          }}
          options={LOSSES_MODES.map((value) => ({ value, label: s.losses[value] }))}
        />
        <TextField
          label={s.losses.percent}
          inputMode="decimal"
          value={percent}
          disabled={mode === 'PROPORTIONAL'}
          error={submitted && normalized === undefined ? s.losses.percentInvalid : undefined}
          onChange={(e) => {
            setPercent(e.target.value);
            setResult(undefined);
          }}
        />
        {mode === 'PROPORTIONAL' && <p className="text-sm text-ink-muted">{s.losses.proportionalHint}</p>}
        {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
        {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
        <div>
          <Button type="submit" disabled={!changed || update.isPending}>
            {s.losses.save}
          </Button>
        </div>
      </form>
    </Panel>
  );
}
