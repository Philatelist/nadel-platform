import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { problemFieldErrors, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea } from '../ui';
import { cashbookProblemMessage } from './expenseErrors';

const MAX_REASON = 500;

export interface ReasonDialogLabels {
  title: string;
  text: string;
  reason: string;
  hint: string;
  required: string;
  cancel: string;
  submit: string;
  saving: string;
}

/** Asks for a required reason, then runs `onSubmit` with it; mount it only while open. */
export function ReasonDialog({
  labels,
  onSubmit,
  onDone,
  onClose,
}: {
  labels: ReasonDialogLabels;
  onSubmit: (reason: string) => Promise<unknown>;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = reason.trim();
  const reasonError = trimmed === '' ? labels.required : undefined;

  const run = useMutation({
    mutationFn: onSubmit,
    onSuccess: onDone,
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : cashbookProblemMessage(t, locale, error));
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (reasonError) return;
    run.mutate(trimmed);
  }

  return (
    <Modal title={labels.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{labels.text}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextArea
          label={labels.reason}
          value={reason}
          rows={4}
          maxLength={MAX_REASON}
          hint={labels.hint}
          error={(submitted ? reasonError : undefined) ?? serverErrors.reason}
          onChange={(e) => {
            setServerErrors({});
            setNotice(undefined);
            setReason(e.target.value);
          }}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {labels.cancel}
          </Button>
          <Button type="submit" variant="danger" disabled={run.isPending}>
            {run.isPending ? labels.saving : labels.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
