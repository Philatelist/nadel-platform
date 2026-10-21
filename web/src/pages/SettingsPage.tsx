import {
  getGetSettingsQueryKey,
  useGetSettings,
  useUpdateSettings,
  type PlotStatusVisibility,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { Button, Notice, PageHeader, Panel, RadioGroup } from '../components/ui';
import { useCanManageMeters } from '../hooks/useCanManageMeters';
import { useCan } from '../me';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const VISIBILITIES = ['OWN_ONLY', 'ALL_PLOTS'] as const satisfies readonly PlotStatusVisibility[];

export function SettingsPage() {
  const t = useT();
  useDocumentTitle(t.settings.title);
  const canSettings = useCan('SETTINGS_MANAGE');
  const canTree = useCanManageMeters();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={t.settings.title} />
      {canSettings && <CooperativeSettings />}
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
      {(data) => <SettingsForm saved={data.memberPlotStatusVisibility} />}
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
