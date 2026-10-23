import { useRunBillingMonth, type MonthRunResult } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useT } from '../../i18n';
import { problemCode, problemMessage } from '../../problem';
import { Modal } from '../Modal';
import { ChargeTypeName } from '../ChargeTypeName';
import { Button, Notice } from '../ui';
import { billingMonthLabel } from './billingMonth';
import { invalidateAfterRun } from './chargeInvalidation';
import { MonthTotals } from './MonthTotals';
import { NotChargedPlot, NotChargedReason } from './NotChargedReason';

/** Previews the month run, then books exactly what was shown (the preview's digest); mount it only while open. */
export function RunPreviewDialog({ month, onClose }: { month: string; onClose: () => void }) {
  const t = useT();
  const r = t.billing.run;
  const queryClient = useQueryClient();
  const [preview, setPreview] = useState<MonthRunResult>();
  const [created, setCreated] = useState<number>();
  const [notice, setNotice] = useState<string>();
  const [outdated, setOutdated] = useState(false);

  const run = useRunBillingMonth({
    mutation: {
      onSuccess: async (result, variables) => {
        if (variables.params.preview) {
          setPreview(result);
          return;
        }
        setCreated(result.created);
        await invalidateAfterRun(queryClient, month);
      },
      onError: (error: unknown) => {
        setNotice(problemMessage(t, error));
        setOutdated(problemCode(error) === 'PREVIEW_OUTDATED');
        // the month may have changed under us: show what the server says now
        void invalidateAfterRun(queryClient, month);
      },
    },
  });
  const { mutate } = run;

  useEffect(() => {
    mutate({ month, params: { preview: true } });
  }, [mutate, month]);

  const previewAgain = () => {
    setNotice(undefined);
    setOutdated(false);
    setPreview(undefined);
    mutate({ month, params: { preview: true } });
  };

  const booking = run.isPending && preview !== undefined;
  return (
    <Modal title={format(r.title, { month: billingMonthLabel(month, t) })} onClose={onClose} size="lg">
      <div className="mt-3 flex flex-col gap-4">
        {created !== undefined ? (
          <Notice tone="success">{format(r.done, { count: created })}</Notice>
        ) : preview === undefined ? (
          !notice && <p className="text-ink-muted">{r.loading}</p>
        ) : (
          <PreviewBody preview={preview} />
        )}
        {notice && <Notice tone="danger">{notice}</Notice>}
        <div className="flex justify-end gap-2">
          {created !== undefined ? (
            <Button onClick={onClose}>{r.close}</Button>
          ) : (
            <>
              <Button variant="secondary" onClick={onClose}>
                {r.cancel}
              </Button>
              {outdated || (notice && preview === undefined) ? (
                <Button onClick={previewAgain}>{r.previewAgain}</Button>
              ) : (
                <Button
                  disabled={preview === undefined || booking}
                  onClick={() => {
                    if (!preview) return;
                    setNotice(undefined);
                    mutate({ month, params: { preview: false }, data: { digest: preview.digest } });
                  }}
                >
                  {booking ? r.saving : r.confirm}
                </Button>
              )}
            </>
          )}
        </div>
      </div>
    </Modal>
  );
}

function PreviewBody({ preview }: { preview: MonthRunResult }) {
  const t = useT();
  const r = t.billing.run;
  return (
    <>
      <p>{format(r.plots, { count: preview.plots })}</p>
      <section className="flex flex-col gap-2">
        <h3 className="font-semibold">{r.totalsTitle}</h3>
        {preview.totals.length === 0 ? (
          <p className="text-ink-muted">{r.nothingNew}</p>
        ) : (
          <MonthTotals totals={preview.totals} total={preview.total} />
        )}
      </section>
      {preview.notCharged.length > 0 && (
        <section className="flex flex-col gap-2">
          <h3 className="font-semibold">{r.notChargedTitle}</h3>
          <p className="text-sm text-ink-muted">{r.notChargedHint}</p>
          <ul className="flex flex-col divide-y divide-line-subtle">
            {preview.notCharged.map((item, index) => (
              <li key={item.id ?? index} className="flex flex-col gap-1 py-2">
                <span className="flex flex-wrap gap-x-2 font-medium">
                  <NotChargedPlot item={item} />
                  {item.chargeType && <ChargeTypeName type={item.chargeType} />}
                </span>
                <NotChargedReason item={item} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
