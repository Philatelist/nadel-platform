import type { PaymentEntryResult } from '@nadel/api-client';
import { format, isZeroMoney } from '@nadel/shared';
import { useState, type ReactNode } from 'react';
import { useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Button, Money } from '../ui';
import { controlClass } from '../ui/fieldStyles';
import { ProposalTable } from './ProposalTable';

/** What the treasurer typed for the parts: the amount per plot and the lines per plot, each only once edited. */
export interface SplitEdits {
  /** Plot ids in the order the parts are sent. */
  order: readonly string[];
  /** Typed part per plot; null while the server's proposed split stands. */
  amounts: Readonly<Record<string, string>> | null;
  /** Typed line amounts per plot and charge; a plot without an entry takes the server's proposal. */
  lines: Readonly<Record<string, Readonly<Record<string, string>>>>;
  /** Charges the treasurer touched or added, per plot. */
  touched: Readonly<Record<string, ReadonlySet<string>>>;
}

/**
 * The parts of a split payment as the server proposes them: per plot the debt, an editable part and, on demand, the
 * allocation of the part (a ProposalTable). Totals, the mismatch of the parts and the prepayment are the server's.
 */
export function SplitParts({
  result,
  edits,
  amountErrors,
  lineErrors,
  onAmount,
  onLine,
}: {
  result: PaymentEntryResult;
  edits: SplitEdits | null;
  /** Problems with a part's amount, by plot id. */
  amountErrors: Readonly<Record<string, string>>;
  /** Problems with a part's lines, by plot id then charge id. */
  lineErrors: Readonly<Record<string, Readonly<Record<string, string>>>>;
  onAmount: (plotId: string, text: string) => void;
  onLine: (plotId: string, chargeId: string, text: string) => void;
}) {
  const t = useT();
  const s = t.payments.split;
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const debts = new Map((result.split?.plots ?? []).map((plot) => [plot.plotId, plot.debt]));

  if (result.parts.length === 0) return <p className="text-ink-muted">{s.noPlots}</p>;

  function toggle(plotId: string) {
    const next = new Set(open);
    if (!next.delete(plotId)) next.add(plotId);
    setOpen(next);
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-[15px]">
        <caption className="sr-only">{s.caption}</caption>
        <thead>
          <tr className="border-b border-line text-sm font-semibold text-ink-muted">
            <th scope="col" className="px-3 py-2 text-left">
              {s.plot}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {s.debt}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {s.part}
            </th>
            <th scope="col" className="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          {result.parts.map((part) => {
            const expanded = open.has(part.plotId);
            const debt = part.debt ?? debts.get(part.plotId);
            const error = amountErrors[part.plotId];
            const lines = edits?.lines[part.plotId];
            return (
              <PartRows
                key={part.plotId}
                expanded={expanded}
                head={
                  <tr className="border-b border-line-subtle align-top">
                    <td className="px-3 py-2 font-medium">
                      <UserContent>{part.plotNumber}</UserContent>
                    </td>
                    <td className="px-3 py-2 text-right">{debt !== undefined && <Money amount={debt} />}</td>
                    <td className="px-3 py-2 text-right">
                      <input
                        type="text"
                        inputMode="decimal"
                        className={`${controlClass} w-32 text-right font-mono`}
                        aria-label={format(s.partFor, { plot: part.plotNumber })}
                        aria-invalid={error !== undefined}
                        value={edits?.amounts?.[part.plotId] ?? part.amount}
                        onChange={(e) => onAmount(part.plotId, e.target.value)}
                      />
                      {error && (
                        <p role="alert" className="mt-1 text-sm text-danger">
                          {error}
                        </p>
                      )}
                      {isZeroMoney(part.amount) && (
                        <p className="mt-1 text-sm text-ink-muted">{s.zeroPartNote}</p>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <Button
                        variant="secondary"
                        aria-expanded={expanded}
                        onClick={() => toggle(part.plotId)}
                        aria-label={`${expanded ? s.collapse : s.expand}: ${part.plotNumber}`}
                      >
                        {expanded ? s.collapse : s.expand}
                      </Button>
                    </td>
                  </tr>
                }
                body={
                  <ProposalTable
                    part={part}
                    values={lines ?? null}
                    touched={edits?.touched[part.plotId] ?? new Set()}
                    lineErrors={lineErrors[part.plotId] ?? {}}
                    amountError={{}}
                    onEdit={(chargeId, text) => onLine(part.plotId, chargeId, text)}
                    onAdd={(chargeId) => onLine(part.plotId, chargeId, '0.00')}
                  />
                }
              />
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PartRows({ head, body, expanded }: { head: ReactNode; body: ReactNode; expanded: boolean }) {
  return (
    <>
      {head}
      {expanded && (
        <tr className="border-b border-line-subtle">
          <td colSpan={4} className="px-3 py-3">
            {body}
          </td>
        </tr>
      )}
    </>
  );
}
