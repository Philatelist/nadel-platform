package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One allocation row (amount above zero) with what is left of it after its releases (feature 015 §2.2), computed by
 * {@link OpenAmounts}. A release is never a state of its own. Pure value.
 *
 * @param id        the allocation
 * @param paymentId the payment whose money it is
 * @param chargeId  the charge it pays
 * @param plotId    the plot of both
 * @param remaining the allocation plus its releases (never below {@code 0.00})
 * @param createdAt when it was written: releases of an excess take the latest allocations first
 */
record AllocationState(UUID id, UUID paymentId, UUID chargeId, UUID plotId, Money remaining, Instant createdAt) {

    AllocationState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether something of it is still placed on its charge. */
    boolean live() {
        return remaining.signum() > 0;
    }
}
