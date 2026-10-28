import { reallocatePayment, type PaymentDetails } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Modal } from '../Modal';
import { Money } from '../ui';
import { AllocationEditor } from './AllocationEditor';
import { invalidateAfterPayment } from './paymentInvalidation';
import { servicePeriodLabel } from './paymentLabels';
import { useLocale } from '../../i18n';

/**
 * Reallocates a payment: shows what it is allocated to now and the server's new proposal as if that were taken back,
 * lets the treasurer edit it, and saves the previewed proposal with its digest. Mount it only while open.
 */
export function ReallocateDialog({
  payment,
  onDone,
  onClose,
}: {
  payment: PaymentDetails;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const r = t.payments.reallocate;
  const queryClient = useQueryClient();
  return (
    <Modal title={r.title} onClose={onClose} size="lg">
      <AllocationEditor
        cacheKey={`reallocate/${payment.id}`}
        preview={(body) => reallocatePayment(payment.id, body, { preview: true })}
        save={(body) => reallocatePayment(payment.id, body, { preview: false })}
        onSaved={async () => {
          await invalidateAfterPayment(queryClient);
          onDone();
        }}
        onClose={onClose}
        submitLabel={r.submit}
        savingLabel={r.saving}
        cancelLabel={r.cancel}
        intro={(result) => (
          <>
            <p className="text-[15px] text-ink-muted">{r.text}</p>
            <section aria-labelledby="realloc-current">
              <h3 id="realloc-current" className="mb-1 font-medium">
                {r.current}
              </h3>
              {result.current.length === 0 ? (
                <p className="text-[15px] text-ink-muted">{r.noCurrent}</p>
              ) : (
                <ul className="flex flex-col gap-1 text-[15px]">
                  {result.current.map((a) => (
                    <li key={a.allocationId} className="flex justify-between gap-3">
                      <span>
                        <ChargeTypeName type={a.chargeType} />,{' '}
                        {servicePeriodLabel(a.serviceFrom, a.serviceTo, t, locale)}
                      </span>
                      <Money amount={a.amount} />
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <h3 className="font-medium">{r.proposal}</h3>
          </>
        )}
      />
    </Modal>
  );
}
