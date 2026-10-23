import { useReverseCharge, type Charge } from '@nadel/api-client';
import { formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { ChargeTypeName } from '../ChargeTypeName';
import { Modal } from '../Modal';
import { Button, Money, Notice, TextArea } from '../ui';
import { invalidateAfterCharge } from './chargeInvalidation';

const MAX_REASON = 500;

/** Reverses a charge with a required reason; mount it only while open. */
export function ReverseChargeDialog({
  charge,
  onDone,
  onClose,
}: {
  charge: Charge;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const r = t.charges.reverse;
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = reason.trim();
  const reasonError = trimmed === '' ? r.required : undefined;

  const reverse = useReverseCharge({
    mutation: {
      onSuccess: async () => {
        await invalidateAfterCharge(queryClient, charge.plotId);
        onDone();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
        // a conflict means the account is out of date: show what the server says now
        void invalidateAfterCharge(queryClient, charge.plotId);
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (reasonError) return;
    reverse.mutate({ id: charge.id, data: { reason: trimmed } });
  }

  return (
    <Modal title={r.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">
          <ChargeTypeName type={charge.chargeType} />, {formatDay(charge.accruedOn, locale)},{' '}
          <Money amount={charge.amount} tone="auto" />
        </p>
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
