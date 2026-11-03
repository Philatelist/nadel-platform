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
 * Money of members moved in or out of the cooperative (feature 018 §2.2, ADR 0019): published inside the transaction
 * that wrote the payment rows, once per payment group (every part of a split is a line) and once per payment reversal,
 * after the rows are flushed. The treasury writes one journal movement per line <b>in the same transaction</b>: its
 * listener is a plain synchronous {@code @EventListener}, never an outbox listener, so the event is never stored and a
 * failure of the listener rolls the payment back (the movement is the same money fact as the payment). Ids, codes,
 * dates and amounts only: no names, notes or reasons.
 *
 * @param tenantId the cooperative; the listener checks it is the one bound to the transaction
 * @param lines    one line per payment row written; never empty
 */
public record PaymentMoneyMoved(UUID tenantId, List<Line> lines) {

    public PaymentMoneyMoved {
        Objects.requireNonNull(tenantId, "tenantId");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A money moved event has at least one line");
        }
    }

    /**
     * One payment row: a payment (kind {@code PAYMENT}, the amount above zero) or a payment reversal (the negated
     * amount).
     *
     * @param ledgerRowId  the payment row written (the payment, or the reversal row)
     * @param paymentId    the payment (kind {@code PAYMENT}): the row itself, or the payment a reversal cancels
     * @param reversal     whether the row is a payment reversal
     * @param source       cash or bank: the money account the money moved on
     * @param amount       signed: above zero for a payment, below zero for a reversal
     * @param movedOn      the day the money moved: the day received for a payment, the day of the reversal (Kyiv)
     *                     for a reversal
     * @param bookingMonth the billing month of the movement: the payment's month, or for a reversal the month
     *                     {@link BookingMonths#bookFor} gives its day
     * @param createdBy    the member who entered the row; null for the system
     * @param createdAt    when the row was written
     */
    public record Line(UUID ledgerRowId, UUID paymentId, boolean reversal, PaymentSource source, Money amount,
            LocalDate movedOn, YearMonth bookingMonth, @Nullable UUID createdBy, Instant createdAt) {

        public Line {
            Objects.requireNonNull(ledgerRowId, "ledgerRowId");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(movedOn, "movedOn");
            Objects.requireNonNull(bookingMonth, "bookingMonth");
            Objects.requireNonNull(createdAt, "createdAt");
            if (Objects.requireNonNull(amount, "amount").signum() == 0 || (amount.signum() < 0) != reversal) {
                throw new IllegalArgumentException("A payment moves money in, its reversal out");
            }
        }
    }
}
