import { Link, useSearchParams } from 'react-router';
import { PaymentForm } from '../components/payments/PaymentForm';
import { PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

/** Enter a payment; `?plotId=` opens the form with that plot already chosen. */
export function PaymentNewPage() {
  const t = useT();
  useDocumentTitle(t.payments.form.title);
  const canView = useCan('PAYMENTS_VIEW');
  const [params] = useSearchParams();
  const plotId = params.get('plotId') ?? undefined;
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.payments.form.title}
        actions={
          canView && (
            <Link to="/payments" className="text-accent underline">
              {t.payments.back}
            </Link>
          )
        }
      />
      <PaymentForm key={plotId ?? ''} initialPlotId={plotId} />
    </div>
  );
}
