package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment (kind {@code PAYMENT}) of a plot with what is allocated of it (feature 015 §2.4), computed by
 * {@link OpenAmounts}. A payment reversal is never a state of its own; it marks its payment {@code reversed}. Pure
 * value.
 *
 * @param id         the payment
 * @param plotId     its plot
 * @param amount     its amount, above zero
 * @param receivedOn the day the money arrived
 * @param createdAt  when it was entered
 * @param reversed   whether a reversal cancels it (its money is gone)
 * @param allocated  the net allocations of it (allocations minus releases)
 */
record PaymentState(UUID id, UUID plotId, Money amount, LocalDate receivedOn, Instant createdAt, boolean reversed,
        Money allocated) {

    PaymentState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receivedOn, "receivedOn");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(allocated, "allocated");
    }

    /** The money of the payment not placed on a charge: amount minus allocated; {@code 0.00} once reversed. */
    Money left() {
        return reversed ? Money.ZERO : amount.minus(allocated);
    }
}
