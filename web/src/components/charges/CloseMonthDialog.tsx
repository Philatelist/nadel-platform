import { useCloseBillingMonth, type BillingMonthChecklist } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice } from '../ui';
import { billingCloseTitle, invalidateAfterClose } from './billingMonth';
import { MonthChecklist } from './MonthChecklist';

/** Confirms closing a month, showing the check list again; mount it only while open. */
export function CloseMonthDialog({
  month,
  checklist,
  onDone,
  onClose,
}: {
  month: string;
  checklist: BillingMonthChecklist;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const c = t.billing.close;
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<string>();

  const close = useCloseBillingMonth({
    mutation: {
      onSuccess: async () => {
        await invalidateAfterClose(queryClient, month);
        onDone();
      },
      onError: (error: unknown) => {
        setNotice(problemMessage(t, error));
        // a conflict means the list is out of date: show what the server says now
        void invalidateAfterClose(queryClient, month);
      },
    },
  });

  return (
    <Modal title={billingCloseTitle(month, t)} onClose={onClose} size="lg">
      <div className="mt-3 flex flex-col gap-4">
        <p className="text-[15px] text-ink-muted">{c.text}</p>
        <MonthChecklist checklist={checklist} />
        {notice && <Notice tone="danger">{notice}</Notice>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {c.cancel}
          </Button>
          <Button
            variant="danger"
            disabled={close.isPending}
            onClick={() => {
              setNotice(undefined);
              close.mutate({ month });
            }}
          >
            {close.isPending ? c.saving : c.confirm}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
