import { reversePayment, type PaymentDetails } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea } from '../ui';
import { invalidateAfterPayment } from './paymentInvalidation';

const MAX_REASON = 500;

/** Reverses a payment with a required reason; mount it only while open. */
export function ReversePaymentDialog({
  payment,
  onDone,
  onClose,
}: {
  payment: PaymentDetails;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const r = t.payments.reverse;
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = reason.trim();
  const reasonError = trimmed === '' ? r.required : undefined;

  const reverse = useMutation({
    mutationFn: (text: string) => reversePayment(payment.id, { reason: text }),
    onSuccess: async () => {
      await invalidateAfterPayment(queryClient);
      onDone();
    },
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      // the card is out of date (already reversed, say): show what the server says now
      void invalidateAfterPayment(queryClient);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (reasonError) return;
    reverse.mutate(trimmed);
  }

  return (
    <Modal title={r.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{r.text}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextArea
          label={r.reason}
          value={reason}
          rows={4}
          maxLength={MAX_REASON}
          hint={r.hint}
          error={(submitted ? reasonError : undefined) ?? serverErrors.reason}
          onChange={(e) => {
            setServerErrors({});
            setNotice(undefined);
            setReason(e.target.value);
          }}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {r.cancel}
          </Button>
          <Button type="submit" variant="danger" disabled={reverse.isPending}>
            {reverse.isPending ? r.saving : r.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
