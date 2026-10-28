import type { AccountPayment, AccountPrepaymentAllocation, Charge } from '@nadel/api-client';
import { chargePeriodNote, formatDay } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Badge, Button, Money } from '../ui';
import { AdjustmentLine } from './AdjustmentLine';
import { mergeAccountEntries } from './accountEntries';
import { chargeEntryLabel, isAdjustment } from './chargeLabels';
import { PaymentRow, PlacementRow } from './PaymentEntryRow';

/** Charges as the server orders them (newest first). Rows wrap on a phone; a reversal is offered only when allowed. */
export function ChargeList({
  charges,
  canReverse,
  onOpen,
  onReverse,
  findCharge,
  payments = [],
  placements = [],
  canOpenPayments = false,
}: {
  charges: readonly Charge[];
  /** Payments and prepayment placements are interleaved with the charges by date. */
  payments?: readonly AccountPayment[];
  placements?: readonly AccountPrepaymentAllocation[];
  /** The payment card is linked only for a caller with `PAYMENTS_VIEW`. */
  canOpenPayments?: boolean;
  canReverse: boolean;
  onOpen: (charge: Charge) => void;
  onReverse: (charge: Charge) => void;
  /** Finds a charge of the loaded list by id (the original of an adjustment). */
  findCharge?: (id: string) => Charge | undefined;
}) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.charges;
  return (
    <ul aria-label={c.listCaption} className="divide-y divide-line-subtle">
      {mergeAccountEntries(charges, payments, placements).map((entry) => {
        if (entry.type === 'payment') {
          return <PaymentRow key={entry.key} payment={entry.payment} canOpen={canOpenPayments} />;
        }
        if (entry.type === 'placement') return <PlacementRow key={entry.key} placement={entry.placement} />;
        const charge = entry.charge;
        const adjustment = isAdjustment(charge);
        const note = adjustment ? undefined : chargePeriodNote(charge, t, locale);
        return (
          <li key={charge.id} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-3">
            <div className="flex min-w-0 flex-col gap-0.5">
              <div className="flex flex-wrap items-center gap-2 font-medium">
                <span>{formatDay(charge.accruedOn, locale)}</span>
                <ChargeTypeName type={charge.chargeType} />
                <Badge tone={charge.kind === 'REVERSAL' ? 'neutral' : 'info'}>
                  {chargeEntryLabel(t, charge)}
                </Badge>
                {charge.reversedBy && <Badge tone="neutral">{c.reversed}</Badge>}
              </div>
              {note && <div className="text-sm text-ink-muted">{note}</div>}
              {adjustment && (
                <AdjustmentLine
                  charge={charge}
                  original={charge.adjusts ? findCharge?.(charge.adjusts.chargeId) : undefined}
                  onOpenOriginal={onOpen}
                />
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {charge.paid !== undefined && charge.open !== undefined && (
                <dl className="flex flex-wrap gap-x-3 text-sm text-ink-muted">
                  <div className="flex gap-1">
                    <dt>{c.charged}</dt>
                    <dd>
                      <Money amount={charge.amount} />
                    </dd>
                  </div>
                  <div className="flex gap-1">
                    <dt>{c.paid}</dt>
                    <dd>
                      <Money amount={charge.paid} />
                    </dd>
                  </div>
                  <div className="flex gap-1">
                    <dt>{c.open}</dt>
                    <dd>
                      <Money amount={charge.open} tone="auto" />
                    </dd>
                  </div>
                </dl>
              )}
              {charge.paid === undefined && (
                <Money amount={charge.amount} tone={adjustment ? 'default' : 'auto'} />
              )}
              <Button variant="secondary" onClick={() => onOpen(charge)}>
                {c.details}
              </Button>
              {canReverse && charge.reversible && (
                <Button variant="danger" onClick={() => onReverse(charge)}>
                  {c.reverse.action}
                </Button>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
