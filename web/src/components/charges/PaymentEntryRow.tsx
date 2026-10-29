import type { AccountPayment, AccountPrepaymentAllocation, PaymentAllocation } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { ChargeTypeName } from '../ChargeTypeName';
import { servicePeriodLabel } from '../payments/paymentLabels';
import { ReceiptButton } from '../payments/ReceiptButton';
import { Badge, Money } from '../ui';

function Lines({ lines, label }: { lines: readonly PaymentAllocation[]; label: string }) {
  const t = useT();
  const { locale } = useLocale();
  if (lines.length === 0) return null;
  return (
    <ul aria-label={label} className="flex flex-col gap-0.5 text-sm text-ink-muted">
      {lines.map((l) => (
        <li key={l.id} className="flex flex-wrap items-baseline gap-x-2">
          <ChargeTypeName type={l.chargeType} />
          <span>{servicePeriodLabel(l.serviceFrom, l.serviceTo, t, locale)}</span>
          <Money amount={l.amount} tone="auto" />
        </li>
      ))}
    </ul>
  );
}

/** A payment or a payment reversal in the account: the day, receipt or bank reference, source, amount and what it paid. */
export function PaymentRow({ payment, canOpen }: { payment: AccountPayment; canOpen: boolean }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.charges;
  const day = formatDay(payment.receivedOn, locale);
  const reference =
    payment.receiptNumber !== undefined
      ? format(t.payments.receiptNumber, { number: payment.receiptNumber })
      : undefined;
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {canOpen ? (
            <Link to={`/payments/${payment.id}`} className="text-accent underline">
              {day}
            </Link>
          ) : (
            <span>{day}</span>
          )}
          <Badge tone={payment.kind === 'PAYMENT_REVERSAL' ? 'neutral' : 'success'}>
            {payment.kind === 'PAYMENT_REVERSAL' ? c.paymentReversalEntry : c.paymentEntry}
          </Badge>
          {reference && <span>{reference}</span>}
          {payment.bankReference && <UserContent>{payment.bankReference}</UserContent>}
          <span className="text-sm font-normal text-ink-muted">{t.payments.source[payment.source]}</span>
          {payment.kind === 'PAYMENT' && payment.reversed && (
            <Badge tone="neutral">{t.payments.reversed}</Badge>
          )}
        </div>
        {payment.reason && (
          <div className="text-sm text-ink-muted">
            <UserContent>{payment.reason}</UserContent>
          </div>
        )}
        <Lines lines={payment.allocations} label={`${c.paymentEntry} ${day}`} />
      </div>
      <div className="flex flex-col items-end gap-2">
        <Money amount={payment.amount} tone="auto" />
        {payment.kind === 'PAYMENT' && <ReceiptButton payment={payment} />}
      </div>
    </li>
  );
}

/** The prepayment placed on charges: a row with its lines. */
export function PlacementRow({ placement }: { placement: AccountPrepaymentAllocation }) {
  const t = useT();
  const { locale } = useLocale();
  const day = formatDay(placement.allocatedOn, locale);
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          <span>{day}</span>
          <Badge tone="info">{t.charges.placedFromPrepayment}</Badge>
        </div>
        <Lines lines={placement.lines} label={`${t.charges.placedFromPrepayment} ${day}`} />
      </div>
      <Money amount={placement.amount} />
    </li>
  );
}
