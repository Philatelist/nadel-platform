import { useState, type FormEvent } from 'react';
import { format } from '@nadel/shared';
import { useT } from '../../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea } from '../ui';

const MAX_REASON = 500;

/** The mutation that reverses a run: the generated `useReverseLossesRun()` and its siblings fit. */
export interface RunReverser {
  mutate(
    variables: { id: string; data: { reason: string } },
    options: { onSuccess?: () => void | Promise<void>; onError?: (error: unknown) => void },
  ): void;
  isPending: boolean;
}

/** Reverses a whole run with a required reason; mount it only while open. */
export function ReverseRunDialog({
  runId,
  range,
  useReverse,
  onDone,
  onClose,
}: {
  runId: string;
  /** The run's range as shown in the list, for the title. */
  range: string;
  useReverse: () => RunReverser;
  /** Called after the run was reversed, before the dialog closes: refresh what the run touched. */
  onDone: () => Promise<unknown> | unknown;
  onClose: () => void;
}) {
  const t = useT();
  const r = t.billing.reverseRun;
  const reverse = useReverse();
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = reason.trim();
  const reasonError = trimmed === '' ? r.required : undefined;

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (reasonError) return;
    reverse.mutate(
      { id: runId, data: { reason: trimmed } },
      {
        onSuccess: async () => {
          await onDone();
          onClose();
        },
        onError: (error) => {
          const fields = problemFieldErrors(t, error);
          setServerErrors(fields);
          setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
          // a conflict means the run is out of date: show what the server says now
          void onDone();
        },
      },
    );
  }

  return (
    <Modal title={format(r.title, { range })} onClose={onClose}>
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
