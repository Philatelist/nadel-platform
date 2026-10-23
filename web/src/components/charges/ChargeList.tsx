import type { Charge } from '@nadel/api-client';
import { chargePeriodNote, formatDay } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Badge, Button, Money } from '../ui';
import { chargeEntryLabel } from './chargeLabels';

/** Charges as the server orders them (newest first). Rows wrap on a phone; a reversal is offered only when allowed. */
export function ChargeList({
  charges,
  canReverse,
  onOpen,
  onReverse,
}: {
  charges: readonly Charge[];
  canReverse: boolean;
  onOpen: (charge: Charge) => void;
  onReverse: (charge: Charge) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.charges;
  return (
    <ul aria-label={c.listCaption} className="divide-y divide-line-subtle">
      {charges.map((charge) => {
        const note = chargePeriodNote(charge, t, locale);
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
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Money amount={charge.amount} tone="auto" />
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
