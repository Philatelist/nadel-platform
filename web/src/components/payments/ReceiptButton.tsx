import { getPaymentReceiptPdf, type SavedPayment } from '@nadel/api-client';
import { useT } from '../../i18n';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemMessage } from '../../problem';
import { Button, Notice } from '../ui';

/** The part of a payment the button needs; a `SavedPayment` and a `PaymentDetails` both fit. */
type ReceiptPayment = Pick<SavedPayment, 'id' | 'source' | 'receiptNumber'>;

/**
 * "Print receipt": downloads the cash receipt PDF of a payment (a split payment's receipt covers its whole group).
 * Shown only for a cash payment that has a receipt number; a bank payment has no receipt.
 */
export function ReceiptButton({ payment }: { payment: ReceiptPayment }) {
  const t = useT();
  const { run, pending, error } = usePdfAction();
  if (payment.source !== 'CASH' || payment.receiptNumber === undefined) return null;
  const fileName = `receipt_${payment.receiptNumber}.pdf`;
  return (
    <>
      <Button
        variant="secondary"
        disabled={pending}
        onClick={() => void run(() => getPaymentReceiptPdf(payment.id), fileName)}
      >
        {pending ? t.receipts.printing : t.receipts.print}
      </Button>
      {error !== undefined && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
    </>
  );
}
