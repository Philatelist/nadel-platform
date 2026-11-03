import { createHandover, type HandoverInput } from '@nadel/api-client';
import { normalizeMoneyInput } from '@nadel/shared';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemFieldErrors, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';
import { invalidateCashbook } from './cashbookInvalidation';
import { cashbookProblemMessage } from './expenseErrors';

/** Hand cash over to the bank: date, amount and the bank slip number; mount it only while open. */
export function HandoverDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const { locale } = useLocale();
  const h = t.cashbook.handover;
  const queryClient = useQueryClient();
  const [date, setDate] = useState(kyivToday());
  const [amountText, setAmountText] = useState('');
  const [documentNumber, setDocumentNumber] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const amount = normalizeMoneyInput(amountText);
  const amountError = amount === undefined || !/[1-9]/.test(amount) ? h.amountInvalid : undefined;
  const dateError = date === '' || date > kyivToday() ? h.dateFuture : undefined;
  const documentError = documentNumber.trim() === '' ? h.documentRequired : undefined;

  const save = useMutation({
    mutationFn: (body: HandoverInput) => createHandover(body),
    onSuccess: async () => {
      await invalidateCashbook(queryClient);
      onClose();
    },
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : cashbookProblemMessage(t, locale, error));
    },
  });

  function changing(set: (value: string) => void) {
    return (value: string) => {
      setServerErrors({});
      setNotice(undefined);
      set(value);
    };
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (amount === undefined || amountError || dateError || documentError) return;
    save.mutate({ date, amount, documentNumber: documentNumber.trim() });
  }

  return (
    <Modal title={h.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{h.text}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={h.date}
          type="date"
          value={date}
          max={kyivToday()}
          error={(submitted ? dateError : undefined) ?? serverErrors.date}
          onChange={(e) => changing(setDate)(e.target.value)}
        />
        <TextField
          label={h.amount}
          inputMode="decimal"
          value={amountText}
          error={(submitted ? amountError : undefined) ?? serverErrors.amount}
          onChange={(e) => changing(setAmountText)(e.target.value)}
        />
        <TextField
          label={h.documentNumber}
          hint={h.documentHint}
          maxLength={64}
          value={documentNumber}
          error={(submitted ? documentError : undefined) ?? serverErrors.documentNumber}
          onChange={(e) => changing(setDocumentNumber)(e.target.value)}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {h.cancel}
          </Button>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? h.saving : h.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
