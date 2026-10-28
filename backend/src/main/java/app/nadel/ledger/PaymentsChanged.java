package app.nadel.ledger;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Payments or allocations changed for these plots of one cooperative (feature 015): a payment was entered (and later a
 * payment reversed or reallocated, prepayment allocated, by hand or automatically); published inside the transaction
 * that wrote them. A listener (the plot status) binds {@code tenantId} with {@code TenantContext.runAs} before it opens
 * its transaction and recomputes the debt and prepayment of the plots from the committed ledger, so a redelivered event
 * changes nothing. Charge bookings keep publishing {@link ChargesBooked}. Carries ids only, no personal data.
 *
 * @param tenantId the cooperative
 * @param plotIds  the plots whose payments or allocations changed; never empty, at most {@value #MAX_PLOTS}
 */
public record PaymentsChanged(UUID tenantId, Set<UUID> plotIds) {

    /** The most plots one event names; a path that changes more publishes several events. */
    public static final int MAX_PLOTS = 500;

    public PaymentsChanged {
        Objects.requireNonNull(tenantId, "tenantId");
        plotIds = Set.copyOf(plotIds);
        if (plotIds.isEmpty()) {
            throw new IllegalArgumentException("A payment change names at least one plot");
        }
        if (plotIds.size() > MAX_PLOTS) {
            throw new IllegalArgumentException("A payment change names at most " + MAX_PLOTS + " plots");
        }
    }
}
