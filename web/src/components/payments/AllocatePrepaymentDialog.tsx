import { allocatePrepayment } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useT } from '../../i18n';
import { Modal } from '../Modal';
import { AllocationEditor } from './AllocationEditor';
import { invalidateAfterPayment } from './paymentInvalidation';

/**
 * Allocates the prepayment of a plot to its open charges by hand: the server's proposal, editable, saved with its
 * digest. Wired into the plot account; mount it only while open.
 */
export function AllocatePrepaymentDialog({ plotId, onDone }: { plotId: string; onDone: () => void }) {
  const t = useT();
  const a = t.payments.allocatePrepayment;
  const queryClient = useQueryClient();
  return (
    <Modal title={a.title} onClose={onDone} size="lg">
      <AllocationEditor
        cacheKey={`prepayment/${plotId}`}
        preview={(body) => allocatePrepayment(plotId, body, { preview: true })}
        save={(body) => allocatePrepayment(plotId, body, { preview: false })}
        onSaved={async () => {
          await invalidateAfterPayment(queryClient);
          onDone();
        }}
        onClose={onDone}
        submitLabel={a.submit}
        savingLabel={a.saving}
        cancelLabel={a.cancel}
        intro={() => <p className="text-[15px] text-ink-muted">{a.text}</p>}
      />
    </Modal>
  );
}
