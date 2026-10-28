import type { SavedPayment } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { UserContent } from '../UserContent';
import { Button, Money, Notice, Panel, buttonClass } from '../ui';

/**
 * What the treasurer sees after a save: for cash the receipt number, large; for a bank payment the saved payment.
 * Everything is the server's answer.
 */
export function PaymentSaved({
  payment,
  bankReference,
  onAnother,
}: {
  payment: SavedPayment;
  bankReference?: string;
  onAnother: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const s = t.payments.saved;
  const canOpen = useCan('PAYMENTS_VIEW');
  return (
    <Panel>
      <Notice tone="success">{s.title}</Notice>
      {payment.receiptNumber !== undefined ? (
        <p className="font-serif text-4xl font-semibold text-ink" data-testid="receipt-number">
          {format(s.receipt, { number: payment.receiptNumber })}
        </p>
      ) : (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2">
          <dt className="text-ink-muted">{t.payments.card.source}</dt>
          <dd>{t.payments.source[payment.source]}</dd>
          <dt className="text-ink-muted">{t.payments.card.amount}</dt>
          <dd>
            <Money amount={payment.amount} />
          </dd>
          <dt className="text-ink-muted">{t.payments.card.receivedOn}</dt>
          <dd>{formatDay(payment.receivedOn, locale)}</dd>
          {bankReference && (
            <>
              <dt className="text-ink-muted">{s.reference}</dt>
              <dd className="break-words">
                <UserContent>{bankReference}</UserContent>
              </dd>
            </>
          )}
        </dl>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {canOpen && (
          <Link to={`/payments/${payment.id}`} className={buttonClass('secondary')}>
            {s.openCard}
          </Link>
        )}
        <Button onClick={onAnother}>{s.another}</Button>
      </div>
    </Panel>
  );
}
