import type { Charge } from '@nadel/api-client';
import {
  calculationLine,
  chargePeriodNote,
  format,
  formatDateTime,
  formatDay,
  formatMeterValue,
  formatMonth,
  formatPercent,
} from '@nadel/shared';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { ChargeTypeName } from '../ChargeTypeName';
import { Modal } from '../Modal';
import { UserContent } from '../UserContent';
import { Badge, Button, Money, Notice } from '../ui';
import { adjustmentLead, chargeEntryLabel, isAdjustment } from './chargeLabels';

/** The calculation of a charge as lines built from the server's strings; mount the dialog only while open. */
export function ChargeDetailDialog({
  charge,
  onClose,
  charges = [],
  onOpenCharge,
}: {
  charge: Charge;
  onClose: () => void;
  /** The loaded list, to open the original of an adjustment and the adjustments of a charge. */
  charges?: readonly Charge[];
  onOpenCharge?: (charge: Charge) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const canReverse = useCan('CHARGES_REVERSE');
  const d = t.charges.detail;
  const { parts, readings, consumption, lossesPercent } = charge.calculation;
  const note = chargePeriodNote(charge, t, locale);
  const singlePart = parts.length === 1;
  const adjustment = isAdjustment(charge);
  const original = charge.adjusts ? charges.find((c) => c.id === charge.adjusts?.chargeId) : undefined;
  const adjustments = (charge.adjustedBy ?? []).flatMap((id) => charges.find((c) => c.id === id) ?? []);
  const showClosedNote =
    canReverse &&
    charge.billingMonthClosed &&
    charge.kind !== 'REVERSAL' &&
    !adjustment &&
    !charge.reversedBy;

  const rows: [string, ReactNode][] = [
    [d.type, <ChargeTypeName key="type" type={charge.chargeType} />],
    [d.kind, chargeEntryLabel(t, charge)],
    [d.amount, <Money key="amount" amount={charge.amount} tone={adjustment ? 'default' : 'auto'} />],
    [d.billingMonth, formatMonth(charge.billingMonth, locale)],
    [d.servicePeriod, `${formatDay(charge.serviceFrom, locale)}–${formatDay(charge.serviceTo, locale)}`],
    [d.accruedOn, formatDay(charge.accruedOn, locale)],
  ];
  if (consumption !== undefined) {
    const unit = parts[0]?.unit;
    const text = formatMeterValue(consumption, locale);
    rows.push([d.consumption, unit && unit !== 'PLOT' ? `${text} ${t.charges.line.unit[unit]}` : text]);
  }
  const { nodeMeterId, runId, consumerMeterId, consumerName, split } = charge.calculation;
  if (nodeMeterId !== undefined) {
    rows.push([
      d.node,
      <Link key="node" to={`/meters/${nodeMeterId}`} className="text-accent underline">
        {d.node}
      </Link>,
    ]);
  }
  if (consumerMeterId !== undefined) {
    rows.push([
      d.consumer,
      <Link key="consumer" to={`/meters/${consumerMeterId}`} className="text-accent underline">
        {consumerName ? <UserContent>{consumerName}</UserContent> : d.consumer}
      </Link>,
    ]);
  }
  if (split !== undefined) rows.push([d.split, t.billing.common.split[split]]);
  if (runId !== undefined) {
    const common = consumerMeterId !== undefined;
    rows.push([
      common ? d.commonRun : d.run,
      <Link
        key="run"
        to={`/charges/${common ? 'common' : 'losses'}?run=${runId}`}
        className="text-accent underline"
      >
        {d.openRun}
      </Link>,
    ]);
  }
  if (lossesPercent !== undefined) rows.push([d.lossesPercent, formatPercent(lossesPercent, locale)]);
  if (charge.reason) rows.push([d.reason, <UserContent key="reason">{charge.reason}</UserContent>]);
  if (charge.adjusts) {
    rows.push([
      d.adjusts,
      original && onOpenCharge ? (
        <button
          key="adjusts"
          type="button"
          className="text-accent underline"
          onClick={() => onOpenCharge(original)}
        >
          {adjustmentLead(t, charge, locale)}
        </button>
      ) : (
        adjustmentLead(t, charge, locale)
      ),
    ]);
  }
  if (adjustments.length > 0 && onOpenCharge) {
    rows.push([
      d.adjustedBy,
      <span key="adjustedBy" className="flex flex-col items-start gap-1">
        {adjustments.map((a) => (
          <button key={a.id} type="button" className="text-accent underline" onClick={() => onOpenCharge(a)}>
            {adjustmentLead(t, a, locale)}
          </button>
        ))}
      </span>,
    ]);
  }
  if (charge.recalculationId) {
    rows.push([
      d.recalculation,
      <Link
        key="recalculation"
        to={`/charges/recalculations/${charge.recalculationId}`}
        className="text-accent underline"
      >
        {d.openCharge}
      </Link>,
    ]);
  }
  rows.push([
    d.createdBy,
    charge.createdByName ? <UserContent key="by">{charge.createdByName}</UserContent> : d.system,
  ]);
  rows.push([d.createdAt, formatDateTime(charge.createdAt, locale)]);

  return (
    <Modal title={d.title} size="lg" onClose={onClose}>
      <div className="mt-3 flex flex-col gap-4 text-[15px]">
        {charge.reversedBy && <Badge tone="neutral">{t.charges.reversed}</Badge>}
        {showClosedNote && <Notice tone="warning">{d.closed}</Notice>}
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-[minmax(9rem,max-content)_1fr]">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">{label}</dt>
              <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{value}</dd>
            </div>
          ))}
        </dl>
        {note && <p className="text-sm text-ink-muted">{note}</p>}
        {charge.reverses && <p className="text-sm text-ink-muted">{d.reverses}</p>}
        {parts.length > 0 && (
          <section aria-label={d.calculation} className="flex flex-col gap-1">
            <h3 className="text-sm font-semibold text-ink-muted">{d.calculation}</h3>
            <ul className="flex flex-col gap-1 font-mono text-sm">
              {parts.map((part, index) => (
                <li key={`${part.from}-${part.to}-${index}`}>
                  {calculationLine(part, singlePart ? charge.calculation.amount : undefined, t, locale)}
                  {part.nodeMeterId !== undefined && part.nodeMeterId !== nodeMeterId && (
                    <>
                      {' '}
                      <Link to={`/meters/${part.nodeMeterId}`} className="text-accent underline">
                        {d.node}
                      </Link>
                    </>
                  )}
                  {part.calculated && <span className="ml-2 text-ink-muted">({d.calculated})</span>}
                </li>
              ))}
            </ul>
            {!singlePart && (
              <p className="flex justify-between gap-3 border-t border-line pt-1 font-semibold">
                <span>{d.total}</span>
                <Money amount={charge.calculation.amount} tone="auto" />
              </p>
            )}
          </section>
        )}
        {readings.length > 0 && (
          <section aria-label={d.readings} className="flex flex-col gap-1">
            <h3 className="text-sm font-semibold text-ink-muted">{d.readings}</h3>
            <ul className="flex flex-col gap-1 text-sm">
              {readings.map((reading, index) => (
                <li key={`${reading.meterId}-${reading.takenOn}-${index}`}>
                  {format(t.charges.line.reading, {
                    date: formatDay(reading.takenOn, locale),
                    value: formatMeterValue(reading.value, locale),
                  })}
                  {reading.calculated && <span className="ml-2 text-ink-muted">({d.calculated})</span>}
                </li>
              ))}
            </ul>
          </section>
        )}
        <div className="flex justify-end">
          <Button variant="secondary" onClick={onClose}>
            {d.close}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
