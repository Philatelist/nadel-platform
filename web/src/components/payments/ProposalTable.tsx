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
        <table className="block w-full border-collapse text-[15px] sm:table">
          <caption className="sr-only">{p.caption}</caption>
          <thead className="sr-only sm:not-sr-only sm:table-header-group">
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
          <tbody className="block sm:table-row-group">
            {listed.length === 0 && automatic.length === 0 ? (
              <tr className="block sm:table-row">
                <td colSpan={4} className="block px-3 py-4 text-center sm:table-cell text-ink-muted">
                  {p.empty}
                </td>
              </tr>
            ) : (
              listed.map((line) => {
                const period = servicePeriodLabel(line.serviceFrom, line.serviceTo, t, locale);
                const error = lineErrors[line.chargeId] ?? amountError[line.chargeId];
                return (
                  <tr
                    key={line.chargeId}
                    className="block rounded-md border border-line-subtle p-3 mb-2 sm:mb-0 sm:table-row sm:rounded-none sm:border-0 sm:border-b sm:p-0 align-top sm:last:border-b-0"
                  >
                    <td
                      data-label={p.charge}
                      className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none"
                    >
                      <ChargeTypeName type={line.chargeType} />
                    </td>
                    <td
                      data-label={p.period}
                      className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none"
                    >
                      {period}
                    </td>
                    <td
                      data-label={p.open}
                      className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none sm:text-right"
                    >
                      <Money amount={line.open} />
                    </td>
                    <td
                      data-label={p.allocate}
                      className="flex flex-col gap-1 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:text-right sm:before:content-none"
                    >
                      {error && (
                        <p role="alert" className="mb-1 text-sm text-danger">
                          {error}
                        </p>
                      )}
                      <input
                        type="text"
                        inputMode="decimal"
                        className={`${controlClass} w-full text-right font-mono sm:w-32`}
                        aria-label={format(p.allocateFor, {
                          charge: chargeTypeName(line.chargeType, t),
                          period,
                        })}
                        aria-invalid={error !== undefined}
                        value={values?.[line.chargeId] ?? line.proposed}
                        onChange={(e) => onEdit(line.chargeId, e.target.value)}
                      />
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
                  className="block rounded-md border border-line-subtle p-3 mb-2 sm:mb-0 sm:table-row sm:rounded-none sm:border-0 sm:border-b sm:p-0 align-top sm:last:border-b-0"
                >
                  <td
                    data-label={p.charge}
                    className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none"
                  >
                    <ChargeTypeName type={line.chargeType} />
                  </td>
                  <td
                    data-label={p.period}
                    className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none"
                  >
                    {period}
                  </td>
                  <td
                    data-label={p.open}
                    className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none sm:text-right"
                  >
                    <Money amount={line.open} />
                  </td>
                  <td
                    data-label={p.allocate}
                    className="flex items-baseline justify-between gap-3 py-1 before:text-sm before:font-semibold before:text-ink-muted before:content-[attr(data-label)] sm:table-cell sm:px-3 sm:py-2 sm:before:content-none sm:text-right"
                  >
                    <Money amount={placed.amount} />
                    <span className="block text-sm text-ink-muted">{p.automatic}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot className="mt-2 block sm:table-footer-group">
            <tr className="block border-t border-line font-medium sm:table-row">
              <th
                scope="row"
                colSpan={4}
                className="block px-3 py-2 text-right text-sm font-medium sm:table-cell sm:whitespace-nowrap sm:text-[15px]"
              >
                {p.allocatedTotal} <Money amount={part.allocatedTotal} /> {p.of}{' '}
                <Money amount={part.amount} />
              </th>
            </tr>
            {totalError && (
              <tr className="block sm:table-row">
                <td colSpan={4} className="block px-3 pb-2 text-right sm:table-cell">
                  <p role="alert" className="text-sm text-danger">
                    {totalError}
                  </p>
                </td>
              </tr>
            )}
            <tr className="flex justify-between sm:table-row font-medium">
              <th scope="row" colSpan={3} className="px-3 py-2 text-left sm:text-right">
                {p.prepayment}
              </th>
              <td className="px-3 py-2 text-right">
                <Money amount={part.prepayment} />
              </td>
            </tr>
            {hasAutomatic && (
              <tr className="block sm:table-row">
                <td
                  colSpan={4}
                  className="block px-3 pb-2 text-right sm:table-cell text-sm font-normal text-ink-muted"
                >
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
