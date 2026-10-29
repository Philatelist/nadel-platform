import { sendMonthInvoices } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { billingMonthLabel } from '../charges/billingMonth';
import { Modal } from '../Modal';
import { Button, Notice } from '../ui';

/**
 * «Send the invoices for {month}»: what the send would create per channel (the server counts, nothing is written), the
 * switch for invoices with nothing due, and the send; afterwards the deliveries screen of the month opens.
 */
export function SendInvoicesDialog({ month, onClose }: { month: string; onClose: () => void }) {
  const t = useT();
  const s = t.invoices.send;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [includeNothingDue, setIncludeNothingDue] = useState(false);
  const preview = useQuery({
    queryKey: ['sendInvoicesPreview', month, includeNothingDue],
    queryFn: () => sendMonthInvoices(month, { preview: true }, { includeNothingDue }),
    gcTime: 0,
  });
  const send = useMutation({
    mutationFn: () => sendMonthInvoices(month, { preview: false }, { includeNothingDue }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['/api/v1/deliveries'] });
      void navigate(`/deliveries?month=${month}`);
    },
  });
  const counts = preview.data?.counts;
  const total = counts === undefined ? 0 : counts.telegram + counts.email + counts.paper;
  const label = billingMonthLabel(month, t);

  return (
    <Modal title={format(s.title, { month: label })} onClose={onClose}>
      <div className="mt-2 flex flex-col gap-3 text-[15px]">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={includeNothingDue}
            onChange={(e) => setIncludeNothingDue(e.target.checked)}
          />
          {s.includeNothingDue}
        </label>
        {preview.isError ? (
          <Notice tone="danger">{problemMessage(t, preview.error)}</Notice>
        ) : counts === undefined ? (
          <p className="text-ink-muted">{s.loading}</p>
        ) : (
          <ul aria-label={s.previewTitle} className="flex flex-col gap-0.5">
            <li>{format(s.telegram, { count: counts.telegram })}</li>
            <li>{format(s.email, { count: counts.email })}</li>
            <li>{format(s.paper, { count: counts.paper })}</li>
            {total === 0 && <li className="text-ink-muted">{s.nothingNew}</li>}
          </ul>
        )}
        {send.isError && <Notice tone="danger">{problemMessage(t, send.error)}</Notice>}
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose}>
          {t.memberForm.cancel}
        </Button>
        <Button
          disabled={send.isPending || counts === undefined || total === 0}
          onClick={() => send.mutate()}
        >
          {send.isPending ? s.sending : s.confirm}
        </Button>
      </div>
    </Modal>
  );
}
