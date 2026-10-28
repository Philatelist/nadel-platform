import {
  getCertificatePdf,
  getListCertificatesQueryKey,
  useIssuePlotCertificate,
  type CertificateRow,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemFieldErrors, problemMessage } from '../../problem';
import { RequisitesWarning } from '../invoices/RequisitesWarning';
import { Button, Notice, Panel, TextField } from '../ui';
import { certificateFileName, kyivToday, missingRequisitesOf } from './certificateFile';

/** Issues a debt certificate for one plot as of a day (default today in Kyiv); only with `CERTIFICATES_ISSUE`. */
export function CertificateForm({ plotId }: { plotId: string }) {
  const t = useT();
  const c = t.certificates;
  const canIssue = useCan('CERTIFICATES_ISSUE');
  const queryClient = useQueryClient();
  const [asOf, setAsOf] = useState(kyivToday);
  const [issued, setIssued] = useState<CertificateRow>();
  const [error, setError] = useState<unknown>();
  const pdf = usePdfAction();
  const issue = useIssuePlotCertificate({
    mutation: {
      onSuccess: async (row) => {
        setError(undefined);
        setIssued(row);
        await queryClient.invalidateQueries({ queryKey: getListCertificatesQueryKey() });
      },
      onError: (caught: unknown) => {
        setIssued(undefined);
        setError(caught);
      },
    },
  });
  if (!canIssue) return null;

  const missing = missingRequisitesOf(error);
  const fieldErrors = error === undefined ? {} : problemFieldErrors(t, error);
  const showGeneral = error !== undefined && missing.length === 0 && fieldErrors.asOf === undefined;

  return (
    <Panel title={c.formTitle}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          issue.mutate({ id: plotId, data: { asOf } });
        }}
      >
        <RequisitesWarning missing={missing} />
        <div className="flex flex-wrap items-end gap-4">
          <TextField
            label={c.asOf}
            type="date"
            value={asOf}
            onChange={(e) => setAsOf(e.target.value)}
            error={fieldErrors.asOf}
          />
          <Button type="submit" disabled={issue.isPending || asOf === ''}>
            {issue.isPending ? c.issuing : c.issue}
          </Button>
        </div>
        {showGeneral && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
        {issued !== undefined && (
          <div className="flex flex-wrap items-center gap-3" role="status">
            <span className="font-medium">{format(c.issued, { number: issued.number })}</span>
            <Button
              variant="secondary"
              disabled={pdf.pending}
              onClick={() =>
                void pdf.run(() => getCertificatePdf(issued.id), certificateFileName(issued.number))
              }
            >
              {pdf.pending ? c.downloading : c.download}
            </Button>
          </div>
        )}
        {pdf.error !== undefined && <Notice tone="danger">{problemMessage(t, pdf.error)}</Notice>}
      </form>
    </Panel>
  );
}
