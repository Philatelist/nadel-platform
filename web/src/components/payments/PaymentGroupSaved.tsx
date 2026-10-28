import type { SavedPayment } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { Button, Money, Notice, Panel } from '../ui';

/**
 * What the treasurer sees after saving a split payment: for cash the one receipt number of the group, large; for a
 * bank payment the date and source; then every saved part with a link to its card. Everything is the server's answer.
 */
export function PaymentGroupSaved({
  payments,
  plotNumbers,
  onAnother,
}: {
  payments: readonly SavedPayment[];
  /** Plot number by plot id, from the preview that was saved. */
  plotNumbers: Readonly<Record<string, string>>;
  onAnother: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const s = t.payments.saved;
  const canOpen = useCan('PAYMENTS_VIEW');
  const receipt = payments.find((p) => p.receiptNumber !== undefined)?.receiptNumber;
  const first = payments[0];
  return (
    <Panel>
      <Notice tone="success">{s.title}</Notice>
      {receipt !== undefined ? (
        <p className="font-serif text-4xl font-semibold text-ink" data-testid="receipt-number">
          {format(s.receipt, { number: receipt })}
        </p>
      ) : (
        first !== undefined && (
          <p className="text-ink-muted">
            {t.payments.source[first.source]}, {formatDay(first.receivedOn, locale)}
          </p>
        )
      )}
      <h3 className="font-medium text-ink">{s.parts}</h3>
      <ul className="flex flex-col gap-2">
        {payments.map((p) => {
          const plot = plotNumbers[p.plotId] ?? '';
          return (
            <li key={p.id} className="flex flex-wrap items-center gap-3">
              {canOpen ? (
                <Link to={`/payments/${p.id}`} className="text-accent underline">
                  {format(s.partLink, { plot })}
                </Link>
              ) : (
                <span>{format(s.partLink, { plot })}</span>
              )}
              <Money amount={p.amount} />
            </li>
          );
        })}
      </ul>
      <div>
        <Button onClick={onAnother}>{s.another}</Button>
      </div>
    </Panel>
  );
}
