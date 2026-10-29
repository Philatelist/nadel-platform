import type { PaymentPartProposal, PaymentProposalLine } from '@nadel/api-client';
import { format, isZeroMoney } from '@nadel/shared';
import { chargeTypeName } from '../../chargeTypeName';
import { useLocale, useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Money, Select } from '../ui';
import { controlClass } from '../ui/fieldStyles';
import { servicePeriodLabel } from './paymentLabels';

/** A line is listed when the server proposed something for it, or the treasurer touched or added it. */
export function isListed(line: PaymentProposalLine, touched: ReadonlySet<string>): boolean {
  return !isZeroMoney(line.proposed) || touched.has(line.chargeId);
}

/**
 * The allocation of one payment part as the server proposes it: the listed charges with an editable "to allocate"
 * amount, a way to add any other open charge, and the totals and prepayment exactly as the latest preview says.
 */
export function ProposalTable({
  part,
  values,
  touched,
  lineErrors,
  amountError,
  totalError,
  onEdit,
  onAdd,
}: {
  part: PaymentPartProposal;
  /** The typed amount per charge (explicit mode); a charge without an entry shows the server's proposal. */
  values: Readonly<Record<string, string>> | null;
  touched: ReadonlySet<string>;
  /** Problems by charge id. */
  lineErrors: Readonly<Record<string, string>>;
  /** Text of a typed amount that is not an amount, by charge id. */
  amountError: Readonly<Record<string, string>>;
  /** A problem of the lines together (they exceed the payment), shown next to the totals. */
  totalError?: string;
  onEdit: (chargeId: string, text: string) => void;
  onAdd: (chargeId: string) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const p = t.payments.proposal;
  const listed = part.lines.filter((line) => isListed(line, touched));
  const addable = part.lines.filter((line) => !isListed(line, touched));
  // The server places the remainder itself; its rows are shown as it answered, read-only.
  const automatic = part.resultLines.filter((line) => line.automatic);
  // The server writes "0.00" for nothing placed automatically; any other digit means something is.
  const hasAutomatic = /[1-9]/.test(part.automaticTotal);

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-[15px]">
          <caption className="sr-only">{p.caption}</caption>
          <thead>
            <tr className="border-b border-line text-sm font-semibold text-ink-muted">
              <th scope="col" className="px-3 py-2 text-left">
                {p.charge}
              </th>
              <th scope="col" className="px-3 py-2 text-left">
                {p.period}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {p.open}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {p.allocate}
              </th>
            </tr>
          </thead>
          <tbody>
            {listed.length === 0 && automatic.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-3 py-4 text-center text-ink-muted">
                  {p.empty}
                </td>
              </tr>
            ) : (
              listed.map((line) => {
                const period = servicePeriodLabel(line.serviceFrom, line.serviceTo, t, locale);
                const error = lineErrors[line.chargeId] ?? amountError[line.chargeId];
                return (
                  <tr key={line.chargeId} className="border-b border-line-subtle align-top last:border-b-0">
                    <td className="px-3 py-2">
                      <ChargeTypeName type={line.chargeType} />
                    </td>
                    <td className="px-3 py-2">{period}</td>
                    <td className="px-3 py-2 text-right">
                      <Money amount={line.open} />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <input
                        type="text"
                        inputMode="decimal"
                        className={`${controlClass} w-32 text-right font-mono`}
                        aria-label={format(p.allocateFor, {
                          charge: chargeTypeName(line.chargeType, t),
                          period,
                        })}
                        aria-invalid={error !== undefined}
                        value={values?.[line.chargeId] ?? line.proposed}
                        onChange={(e) => onEdit(line.chargeId, e.target.value)}
                      />
                      {error && (
                        <p role="alert" className="mt-1 text-sm text-danger">
                          {error}
                        </p>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
            {automatic.map((placed) => {
              const line = part.lines.find((x) => x.chargeId === placed.chargeId);
              if (line === undefined) return null;
              const period = servicePeriodLabel(line.serviceFrom, line.serviceTo, t, locale);
              return (
                <tr
                  key={`auto-${placed.chargeId}`}
                  className="border-b border-line-subtle align-top last:border-b-0"
                >
                  <td className="px-3 py-2">
                    <ChargeTypeName type={line.chargeType} />
                  </td>
                  <td className="px-3 py-2">{period}</td>
                  <td className="px-3 py-2 text-right">
                    <Money amount={line.open} />
                  </td>
                  <td className="px-3 py-2 text-right">
                    <Money amount={placed.amount} />
                    <span className="block text-sm text-ink-muted">{p.automatic}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t border-line font-medium">
              <th scope="row" colSpan={3} className="px-3 py-2 text-right">
                {p.allocatedTotal}
              </th>
              <td className="whitespace-nowrap px-3 py-2 text-right">
                <Money amount={part.allocatedTotal} /> {p.of} <Money amount={part.amount} />
              </td>
            </tr>
            {totalError && (
              <tr>
                <td colSpan={4} className="px-3 pb-2 text-right">
                  <p role="alert" className="text-sm text-danger">
                    {totalError}
                  </p>
                </td>
              </tr>
            )}
            <tr className="font-medium">
              <th scope="row" colSpan={3} className="px-3 py-2 text-right">
                {p.prepayment}
              </th>
              <td className="px-3 py-2 text-right">
                <Money amount={part.prepayment} />
              </td>
            </tr>
            {hasAutomatic && (
              <tr>
                <td colSpan={4} className="px-3 pb-2 text-right text-sm font-normal text-ink-muted">
                  {p.automaticNote}
                </td>
              </tr>
            )}
          </tfoot>
        </table>
      </div>
      {addable.length > 0 && (
        <div className="max-w-md">
          <Select
            label={p.add}
            value=""
            onChange={(e) => {
              if (e.target.value) onAdd(e.target.value);
            }}
          >
            <option value="">{p.addPlaceholder}</option>
            {addable.map((line) => (
              <option key={line.chargeId} value={line.chargeId}>
                {`${chargeTypeName(line.chargeType, t)}, ${servicePeriodLabel(line.serviceFrom, line.serviceTo, t, locale)}`}
              </option>
            ))}
          </Select>
        </div>
      )}
    </div>
  );
}
