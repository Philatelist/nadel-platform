import { useRejectReading, type MeterReading } from '@nadel/api-client';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea } from '../ui';

const MAX_REASON = 500;

/** Rejects a member's pending reading with a required reason the owner will see; mount it only while open. */
export function RejectReadingDialog({
  reading,
  onRejected,
  onClose,
}: {
  reading: MeterReading;
  onRejected: () => void | Promise<void>;
  onClose: () => void;
}) {
  const t = useT();
  const a = t.readings.approvals;
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = reason.trim();
  const reasonError = trimmed === '' ? a.rejectRequired : undefined;

  const reject = useRejectReading({
    mutation: {
      onSuccess: async () => {
        await onRejected();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (reasonError) return;
    reject.mutate({ id: reading.id, data: { reason: trimmed } });
  }

  return (
    <Modal title={a.rejectTitle} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextArea
          label={a.rejectReason}
          value={reason}
          rows={4}
          maxLength={MAX_REASON}
          hint={a.rejectHint}
          error={(submitted ? reasonError : undefined) ?? serverErrors.reason}
          onChange={(e) => {
            setServerErrors({});
            setNotice(undefined);
            setReason(e.target.value);
          }}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {t.meters.cancel}
          </Button>
          <Button type="submit" variant="danger" disabled={reject.isPending}>
            {reject.isPending ? t.meters.saving : a.rejectSubmit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
