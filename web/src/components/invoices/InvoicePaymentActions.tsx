import type { InvoicePayment } from '@nadel/api-client';
import { useT } from '../../i18n';
import { buttonClass } from '../ui';
import { CopyButton } from './CopyButton';

const NBU_LINK_PREFIX = 'https://qr.bank.gov.ua/';

/**
 * «Pay in the bank app», «Copy IBAN» and «Copy payment purpose» for a member invoice. Nothing is rendered without
 * `payment`; the bank link is only offered when it is an NBU payment link.
 */
export function InvoicePaymentActions({ payment }: { payment?: InvoicePayment }) {
  const t = useT();
  if (payment === undefined) return null;
  const p = t.invoices.pay;
  return (
    <div className="flex basis-full flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-start">
      {payment.link.startsWith(NBU_LINK_PREFIX) && (
        <a
          href={payment.link}
          target="_blank"
          rel="noopener noreferrer"
          className={`${buttonClass('primary')} w-full sm:w-auto`}
        >
          {p.payInBank}
        </a>
      )}
      <CopyButton value={payment.iban} label={p.copyIban} />
      <CopyButton value={payment.purpose} label={p.copyPurpose} />
    </div>
  );
}
