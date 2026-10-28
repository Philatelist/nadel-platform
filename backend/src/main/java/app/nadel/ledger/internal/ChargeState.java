package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What allocation needs to know about one {@code REGULAR} or {@code OPENING} charge of a plot (feature 015 §2.2),
 * computed by {@link OpenAmounts}: its effective amount (the charge plus its adjustments, {@code 0.00} once reversed)
 * and what is allocated to it (net of releases). Reversals and adjustments are never states of their own; they only
 * change the effective amount of their charge. Pure value.
 *
 * @param id           the charge
 * @param plotId       its plot
 * @param kind         {@code REGULAR} or {@code OPENING}
 * @param chargeTypeId its charge type
 * @param typePosition the place of the charge type in the cooperative's order of charge types (0 first);
 *                     {@link Integer#MAX_VALUE} for a type the order does not know, so it comes last
 * @param accruedOn    the day its debt counts from
 * @param serviceFrom  the start of its service period; null falls back to {@code accruedOn}
 * @param createdAt    when it was booked
 * @param effective    the charge plus its adjustments; {@code 0.00} when reversed
 * @param allocated    the net allocations to it (allocations minus releases)
 * @param reversed     whether a reversal cancels it (then nothing can be allocated to it)
 */
record ChargeState(UUID id, UUID plotId, ChargeKind kind, UUID chargeTypeId, int typePosition, LocalDate accruedOn,
        @Nullable LocalDate serviceFrom, Instant createdAt, Money effective, Money allocated, boolean reversed) {

    ChargeState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(chargeTypeId, "chargeTypeId");
        Objects.requireNonNull(accruedOn, "accruedOn");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(allocated, "allocated");
        if (kind != ChargeKind.REGULAR && kind != ChargeKind.OPENING) {
            throw new IllegalArgumentException("Only a regular or an opening charge receives allocations");
        }
        if (reversed && effective.signum() != 0) {
            throw new IllegalArgumentException("A reversed charge has the effective amount 0.00");
        }
    }

    /** What is still owed on the charge: effective minus allocated. */
    Money open() {
        return effective.minus(allocated);
    }

    /** Whether money can be allocated to it: a charge that is not reversed. */
    boolean allocatable() {
        return !reversed;
    }

    /** The start of the service period, or {@code accruedOn} when there is none. */
    LocalDate serviceStart() {
        return serviceFrom != null ? serviceFrom : accruedOn;
    }
}
