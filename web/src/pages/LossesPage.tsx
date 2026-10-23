import {
  getGetLossesRunQueryKey,
  useListLossesRuns,
  useReverseLossesRun,
  useRunLosses,
  type LossesRunResult,
  type LossesRunSummary,
} from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { invalidateAfterRangeRun } from '../components/charges/chargeInvalidation';
import { LossesNodesTable, LossesWarnings } from '../components/charges/LossesNodes';
import { LossesRunDetailsView } from '../components/charges/LossesRunDetailsView';
import { ReverseRunDialog } from '../components/charges/ReverseRunDialog';
import { RunHistory, runRange } from '../components/charges/RunHistory';
import { RunRangePage } from '../components/charges/RunRangePage';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';

/** `/charges/losses`: preview, book and reverse the proportional losses of the meter tree for a date range. */
export function LossesPage() {
  const t = useT();
  const { locale } = useLocale();
  const l = t.billing.losses;
  const queryClient = useQueryClient();
  const canSettings = useCan('SETTINGS_MANAGE');
  const [params] = useSearchParams();
  const runner = useRunLosses();
  const list = useListLossesRuns();

  return (
    <RunRangePage<LossesRunResult>
      title={l.heading}
      description={l.description}
      runner={runner}
      previewTitle={(range) =>
        format(l.previewTitle, { from: formatDay(range.from, locale), to: formatDay(range.to, locale) })
      }
      renderPreview={(result) => (
        <>
          <LossesWarnings nodes={result.nodes} />
          <LossesNodesTable nodes={result.nodes} />
        </>
      )}
      hasLines={(result) => result.nodes.length > 0}
      partial={(result) => {
        const ids = result.nodes.filter((n) => n.outcome !== 'ALREADY_CHARGED').map((n) => n.meterId);
        return { blocked: ids.length < result.nodes.length, ids };
      }}
      blockedTexts={{ note: l.overlapNote, action: l.excludeOverlap, nothing: l.nothingToBook }}
      problemHint={(code) =>
        code === 'LOSSES_MODE_NOT_PROPORTIONAL' && canSettings ? (
          <Link to="/settings" className="text-accent underline">
            {l.openSettings}
          </Link>
        ) : undefined
      }
      history={
        <RunHistory<LossesRunSummary>
          query={list}
          initialOpenId={params.get('run')}
          counts={(run) =>
            format(t.billing.runHistory.counts, {
              plots: run.plots,
              charged: run.chargedNodes,
              skipped: run.skippedNodes,
            })
          }
          renderDetails={(run) => <LossesRunDetailsView id={run.id} />}
          renderReverse={(run, onClose) => (
            <ReverseRunDialog
              runId={run.id}
              range={runRange(run, t.billing.runHistory.range, locale)}
              useReverse={useReverseLossesRun}
              onClose={onClose}
              onDone={async () => {
                await Promise.all([
                  invalidateAfterRangeRun(queryClient, run.billingMonth),
                  queryClient.invalidateQueries({ queryKey: getGetLossesRunQueryKey(run.id) }),
                ]);
              }}
            />
          )}
        />
      }
    />
  );
}
