package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the invoice of one plot for one billing month needs of the ledger ({@link LedgerReadModel#invoiceLedgers},
 * feature 016 §2.3, technical considerations 2.3), by the <b>booking-month rule</b>
 * ({@code docs/domain/money-rules.md}, «Рахунки та довідки»): a charge and a payment count in their own billing
 * month; an allocation or a release counts in the latest of its payment's billing month, its charge's billing month
 * and the month that was open when it was created (the month after the last month closed at or before then); only
 * rows created at or before {@code asOf} count. A closed month therefore never changes. Ids, codes and {@code Money}
 * only; the caller names the charge types.
 *
 * @param plotId          the plot
 * @param month           the billing month of the invoice
 * @param status          the month's status as of now; null when the month has no row yet (nothing booked in it)
 * @param closedAt        when the month was closed; null while it is open
 * @param types           per charge type with any movement up to the month: start, charged, paid and end
 *                        ({@link TypeMovement}); in no particular order (the caller orders them)
 * @param lines           every charge booked in the month (any kind), with what it was calculated from
 * @param prepaymentAtEnd the money of the plot on no charge at the end of the month: payments booked in or before the
 *                        month minus allocations counted in or before it, never below {@code 0.00}
 * @param receivedAfter   the payments booked after the month and created at or before {@code asOf} that were not
 *                        reversed by then, oldest first
 * @param payments        every payment row booked in the month and created at or before {@code asOf}, as the ledger
 *                        holds them: a payment with its amount and a payment reversal as its own row with the negated
 *                        amount (and the receipt number or bank reference of the payment it reverses); in the order
 *                        received, then created
 */
public record InvoiceLedger(UUID plotId, YearMonth month, @Nullable MonthStatus status, @Nullable Instant closedAt,
        List<TypeMovement> types, List<Line> lines, Money prepaymentAtEnd, List<LaterPayment> receivedAfter,
        List<MonthPayment> payments) {

    public InvoiceLedger {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(month, "month");
        types = List.copyOf(types);
        lines = List.copyOf(lines);
        Objects.requireNonNull(prepaymentAtEnd, "prepaymentAtEnd");
        receivedAfter = List.copyOf(receivedAfter);
        payments = List.copyOf(payments);
    }

    /**
     * The figures of one charge type for the month.
     *
     * @param chargeTypeId the charge type
     * @param start        charges booked before the month minus allocations counted before it
     * @param charged      charges booked in the month (regular charges, reversals, adjustments, opening balances)
     * @param paid         allocations counted in the month (releases negative)
     * @param end          {@code start + charged - paid}
     */
    public record TypeMovement(UUID chargeTypeId, Money start, Money charged, Money paid, Money end) {

        public TypeMovement {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(charged, "charged");
            Objects.requireNonNull(paid, "paid");
            Objects.requireNonNull(end, "end");
        }
    }

    /** The kind of a charge (column {@code charge.kind}). */
    public enum LineKind {
        REGULAR,
        REVERSAL,
        OPENING,
        ADJUSTMENT
    }

    /**
     * What produced a charge: the charge's own source, and for a reversal or an adjustment the source of the charge it
     * corrects (whose own source is {@code MANUAL_REVERSAL} or {@code RECALCULATION}).
     */
    public enum LineSource {
        TARIFF,
        CONSUMPTION,
        LOSSES,
        COMMON_CONSUMER,
        OPENING
    }

    /**
     * One charge booked in the month, with what its stored calculation says.
     *
     * @param chargeId       the charge
     * @param chargeTypeId   its charge type
     * @param kind           its kind
     * @param source         what produced it (for a correction: the corrected charge's source)
     * @param amount         its amount with its sign
     * @param serviceFrom    the first service day
     * @param serviceTo      the last service day
     * @param corrects       for a reversal or an adjustment of a charge booked in an earlier month: that month; null
     *                       otherwise
     * @param parts          the parts of the stored calculation (tariff basis, quantity, unit, rate, days)
     * @param readings       the meter values of the stored calculation (an interval: its two points)
     * @param lossesPercent  the percentage of a fixed-percentage losses charge ({@code "5.00"}); null otherwise
     * @param consumption    the consumption a fixed-percentage losses charge is taken of ({@code "338.300"}); null
     *                       otherwise
     * @param consumerName   the common consumer's name of a common consumer share (as booked); null otherwise
     */
    public record Line(UUID chargeId, UUID chargeTypeId, LineKind kind, LineSource source, Money amount,
            LocalDate serviceFrom, LocalDate serviceTo, @Nullable YearMonth corrects, List<Part> parts,
            List<Reading> readings, @Nullable String lossesPercent, @Nullable String consumption,
            @Nullable String consumerName) {

        public Line {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(serviceFrom, "serviceFrom");
            Objects.requireNonNull(serviceTo, "serviceTo");
            parts = List.copyOf(parts);
            readings = List.copyOf(readings);
        }
    }

    /**
     * One part of a stored calculation, as stored (codes and plain decimal strings).
     *
     * @param basis     the tariff basis code ({@code PER_AREA_SOTKA}, {@code PER_KWH}, ...); null when not stored
     * @param frequency the frequency code ({@code MONTHLY}, {@code YEARLY}, ...); null when not stored
     * @param quantity  the quantity ({@code "6.00"} sotkas, {@code "338.300"} kWh, {@code "1"} plot); null when not
     *                  stored
     * @param unit      the unit code ({@code SOTKA}, {@code PLOT}, {@code KWH}, {@code M3}); null when not stored
     * @param rate      the rate ({@code "50.0000"}); null when not stored
     * @param days      the days the part counts for
     * @param from      the first day
     * @param to        the last day
     */
    public record Part(@Nullable String basis, @Nullable String frequency, @Nullable String quantity,
            @Nullable String unit, @Nullable String rate, int days, LocalDate from, LocalDate to) {

        public Part {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** A meter value a charge was calculated from: the meter, the day and the value ({@code "8205.000"}). */
    public record Reading(UUID meterId, LocalDate takenOn, String value) {

        public Reading {
            Objects.requireNonNull(meterId, "meterId");
            Objects.requireNonNull(takenOn, "takenOn");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A payment booked after the month and received before the invoice (it lowers «До сплати»).
     *
     * @param paymentId  the payment (kind {@code PAYMENT})
     * @param receivedOn the day it was received
     * @param bookedIn   the billing month it is booked in, after the invoice's month
     * @param amount     its amount, above zero
     */
    public record LaterPayment(UUID paymentId, LocalDate receivedOn, YearMonth bookedIn, Money amount) {

        public LaterPayment {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(receivedOn, "receivedOn");
            Objects.requireNonNull(bookedIn, "bookedIn");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * A payment row booked in the month of the invoice (feature 016 §2.3, «Оплати за {місяць}»): what a member matches
     * with their bank or their cash receipt. No payer, no note: only the ids, the day, the amount and the reference.
     *
     * @param paymentId     the payment row
     * @param reverses      for a payment reversal ({@code PAYMENT_REVERSAL}): the payment it reverses; null for a
     *                      payment
     * @param receivedOn    the day it was received (a reversal keeps the day of its payment)
     * @param amount        its amount with its sign: above zero for a payment, below zero for a reversal
     * @param receiptNumber the cash receipt number (of the reversed payment for a reversal); null for a bank payment
     * @param bankReference the bank reference (of the reversed payment for a reversal); null when none was entered
     */
    public record MonthPayment(UUID paymentId, @Nullable UUID reverses, LocalDate receivedOn, Money amount,
            @Nullable Long receiptNumber, @Nullable String bankReference) {

        public MonthPayment {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(receivedOn, "receivedOn");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
