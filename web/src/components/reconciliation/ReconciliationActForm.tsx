import { getPlotReconciliationActPdf } from '@nadel/api-client';
import { useState } from 'react';
import { useT } from '../../i18n';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemFieldErrors, problemMessage } from '../../problem';
import { kyivToday, missingRequisitesOf } from '../certificates/certificateFile';
import { RequisitesWarning } from '../invoices/RequisitesWarning';
import { Button, Notice, Panel, TextField } from '../ui';

/** The first day of the year of `today` (`YYYY-MM-DD`): a formatting default, not date arithmetic. */
export function yearStart(today: string): string {
  return `${today.slice(0, 4)}-01-01`;
}

/** Downloads the reconciliation act of one plot for a period; the server decides who may and checks the period. */
export function ReconciliationActForm({ plotId }: { plotId: string }) {
  const t = useT();
  const r = t.reconciliationAct;
  const [to, setTo] = useState(kyivToday);
  const [from, setFrom] = useState(() => yearStart(kyivToday()));
  const pdf = usePdfAction();

  const missing = missingRequisitesOf(pdf.error);
  const fieldErrors = pdf.error === undefined ? {} : problemFieldErrors(t, pdf.error);
  const showGeneral =
    pdf.error !== undefined &&
    missing.length === 0 &&
    fieldErrors.from === undefined &&
    fieldErrors.to === undefined;

  return (
    <Panel title={r.formTitle}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void pdf.run(
            () => getPlotReconciliationActPdf(plotId, { from, to }),
            `reconciliation-act_${from}_${to}.pdf`,
          );
        }}
      >
        <RequisitesWarning missing={missing} />
        <div className="flex flex-wrap items-end gap-4">
          <TextField
            label={r.from}
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            error={fieldErrors.from}
          />
          <TextField
            label={r.to}
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            error={fieldErrors.to}
          />
          <Button type="submit" disabled={pdf.pending || from === '' || to === ''}>
            {pdf.pending ? r.downloading : r.download}
          </Button>
        </div>
        {showGeneral && <Notice tone="danger">{problemMessage(t, pdf.error)}</Notice>}
      </form>
    </Panel>
  );
}
