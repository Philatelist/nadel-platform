import { useSendInvoice, type InvoiceRow } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { ConfirmDialog } from '../ConfirmDialog';
import { formatUser } from '../UserContent';
import { Button, Notice } from '../ui';

/** «Send» on one invoice: a confirmation, then the deliveries the send created per channel. */
export function InvoiceSendButton({ row }: { row: InvoiceRow }) {
  const t = useT();
  const s = t.invoices.send;
  const [confirming, setConfirming] = useState(false);
  const send = useSendInvoice({ mutation: { onSettled: () => setConfirming(false) } });
  const counts = send.data?.counts;
  return (
    <>
      <Button variant="secondary" onClick={() => setConfirming(true)}>
        {s.invoice}
      </Button>
      {confirming && (
        <ConfirmDialog
          title={formatUser(s.invoiceTitle, { number: row.number })}
          message={formatUser(s.invoiceMessage, { plot: row.plotNumber })}
          confirmLabel={s.invoice}
          busy={send.isPending}
          onConfirm={() => send.mutate({ id: row.id })}
          onCancel={() => setConfirming(false)}
        />
      )}
      {counts !== undefined && (
        <div className="basis-full">
          <Notice tone="success">{format(s.invoiceResult, { ...counts })}</Notice>
        </div>
      )}
      {send.isError && (
        <div className="basis-full">
          <Notice tone="danger">{problemMessage(t, send.error)}</Notice>
        </div>
      )}
    </>
  );
}
