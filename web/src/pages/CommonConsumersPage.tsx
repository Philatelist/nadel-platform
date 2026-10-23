import {
  getGetCommonConsumerRunQueryKey,
  useListCommonConsumerRuns,
  useReverseCommonConsumerRun,
  useRunCommonConsumers,
  type CommonConsumerRunResult,
  type CommonConsumerRunSummary,
} from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { invalidateAfterRangeRun } from '../components/charges/chargeInvalidation';
import { CommonConsumerRunDetailsView } from '../components/charges/CommonConsumerRunDetailsView';
import { CommonConsumersTable, CommonConsumerWarnings } from '../components/charges/CommonConsumers';
import { ReverseRunDialog } from '../components/charges/ReverseRunDialog';
import { RunHistory, runRange } from '../components/charges/RunHistory';
import { RunRangePage } from '../components/charges/RunRangePage';
import { useLocale, useT } from '../i18n';

/** `/charges/common`: preview, book and reverse the charges of the common consumers for a date range. */
export function CommonConsumersPage() {
  const t = useT();
  const { locale } = useLocale();
  const c = t.billing.common;
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const runner = useRunCommonConsumers();
  const list = useListCommonConsumerRuns();

  return (
    <RunRangePage<CommonConsumerRunResult>
      title={c.heading}
      description={c.description}
      runner={runner}
      idsField="consumerIds"
      hasLines={(result) => result.consumers.length > 0}
      previewTitle={(range) =>
        format(c.previewTitle, { from: formatDay(range.from, locale), to: formatDay(range.to, locale) })
      }
      renderPreview={(result) => (
        <>
          {result.noTariff && (
            <div className="flex flex-col items-start gap-1">
              <p className="font-semibold">{c.noTariff}</p>
              <Link to="/tariffs" className="text-accent underline">
                {c.openTariffs}
              </Link>
            </div>
          )}
          <CommonConsumerWarnings consumers={result.consumers} />
          <CommonConsumersTable consumers={result.consumers} noTariff={result.noTariff} />
        </>
      )}
      partial={(result) => {
        const rest = result.consumers.filter((line) => line.outcome !== 'ALREADY_CHARGED');
        if (result.noTariff) return { blocked: true, ids: [], notice: c.noTariffNotice };
        return {
          blocked: rest.length < result.consumers.length,
          // the consumers that are not charged yet, unknown ones included, so they are queued
          ids: rest.map((line) => line.meterId),
        };
      }}
      blockedTexts={{ note: c.overlapNote, action: c.excludeOverlap, nothing: c.nothingToBook }}
      history={
        <RunHistory<CommonConsumerRunSummary>
          query={list}
          initialOpenId={params.get('run')}
          counts={(run) =>
            format(c.counts, {
              plots: run.plots,
              charged: run.chargedConsumers,
              skipped: run.skippedConsumers,
            })
          }
          renderDetails={(run) => <CommonConsumerRunDetailsView id={run.id} />}
          renderReverse={(run, onClose) => (
            <ReverseRunDialog
              runId={run.id}
              range={runRange(run, t.billing.runHistory.range, locale)}
              useReverse={useReverseCommonConsumerRun}
              onClose={onClose}
              onDone={async () => {
                await Promise.all([
                  invalidateAfterRangeRun(queryClient, run.billingMonth),
                  queryClient.invalidateQueries({ queryKey: getGetCommonConsumerRunQueryKey(run.id) }),
                ]);
              }}
            />
          )}
        />
      }
    />
  );
}
