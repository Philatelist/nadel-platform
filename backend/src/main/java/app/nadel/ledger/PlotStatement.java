package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The statement of one plot for a period ({@link LedgerReadModel#plotStatement}, the reconciliation act of feature 018
 * §2.10): the balance at the start (as of the day before {@code from}), every ledger row dated in the period, and the
 * balance at the end (as of {@code to}), both ends exactly as {@link LedgerReadModel#debtsAsOf} with every charge type
 * counts them, so they equal the plot balances report on those days. A charge row is dated on its debt date
 * ({@code accrued_on}; a reversal and an adjustment carry the date of the charge they correct), a payment row on the day
 * it was received (a payment reversal carries the day of the payment it reverses). Hence the net balance (debt minus
 * prepayment) at the end is the one at the start plus {@link #charged()} minus {@link #paid()}. Ids, codes and
 * {@code Money} only, no personal data; the caller names the charge types.
 *
 * @param plotId  the plot
 * @param from    the first day of the period, inclusive
 * @param to      the last day of the period, inclusive
 * @param opening the plot's debts as of the day before {@code from}
 * @param closing the plot's debts as of {@code to}
 * @param lines   the rows dated in the period, by date, then charges before payments, then the time they were booked,
 *                then id
 */
public record PlotStatement(UUID plotId, LocalDate from, LocalDate to, PlotDebts opening, PlotDebts closing,
        List<Line> lines) {

    public PlotStatement {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(closing, "closing");
        lines = List.copyOf(lines);
    }

    /** The turnover «Нараховано» of the period: the charged amounts of the lines, signed. */
    public Money charged() {
        return lines.stream().map(Line::charged).reduce(Money.ZERO, Money::plus);
    }

    /** The turnover «Сплачено» of the period: the paid amounts of the lines, signed (a payment reversal negative). */
    public Money paid() {
        return lines.stream().map(Line::paid).reduce(Money.ZERO, Money::plus);
    }

    /** The net balance at the start: the debt minus the prepayment (above zero a debt, below zero an overpayment). */
    public Money openingNet() {
        return net(opening);
    }

    /** The net balance at the end: the debt minus the prepayment. */
    public Money closingNet() {
        return net(closing);
    }

    private static Money net(PlotDebts debts) {
        return debts.total().minus(debts.prepayment());
    }

    /** What a line of the statement is: the description key of the act. */
    public enum LineKind {

        /** An opening balance (charge kind {@code OPENING}). */
        OPENING,

        /** A regular charge. */
        CHARGE,

        /** An adjustment of a charge by a recalculation, either sign. */
        ADJUSTMENT,

        /** The reversal of a charge (negative). */
        CHARGE_REVERSAL,

        /** A payment received. */
        PAYMENT,

        /** The reversal of a payment (negative), dated on the day of the payment it reverses. */
        PAYMENT_REVERSAL
    }

    /**
     * One ledger row of the period.
     *
     * @param date          the debt date of a charge row, the day received of a payment row
     * @param kind          what the row is
     * @param rowId         the {@code charge} or {@code payment} row
     * @param chargeTypeId  the charge type of a charge row; null for a payment row
     * @param serviceFrom   the first service day of a charge row (of the charge it corrects for a correction); null for
     *                      a payment row
     * @param serviceTo     the last service day of a charge row; null for a payment row
     * @param charged       the amount of a charge row, signed («Нараховано»); {@code 0.00} for a payment row
     * @param paid          the amount of a payment row, signed («Сплачено»); {@code 0.00} for a charge row
     * @param paymentSource the source of a payment row; null for a charge row
     * @param receiptNumber the receipt number of a cash payment (for a reversal, of the payment it reverses); null
     *                      otherwise
     * @param bankReference the bank document number of a bank payment (for a reversal, of the payment it reverses);
     *                      null when none was entered
     */
    public record Line(LocalDate date, LineKind kind, UUID rowId, @Nullable UUID chargeTypeId,
            @Nullable LocalDate serviceFrom, @Nullable LocalDate serviceTo, Money charged, Money paid,
            @Nullable PaymentSource paymentSource, @Nullable Long receiptNumber, @Nullable String bankReference) {

        public Line {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(rowId, "rowId");
            Objects.requireNonNull(charged, "charged");
            Objects.requireNonNull(paid, "paid");
        }

        /** Whether the row is a payment or a payment reversal. */
        public boolean payment() {
            return kind == LineKind.PAYMENT || kind == LineKind.PAYMENT_REVERSAL;
        }
    }
}
