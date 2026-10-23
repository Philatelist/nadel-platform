package app.nadel.ledger;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Charges were booked or reversed for these plots of one cooperative; published inside the transaction that wrote them.
 * A listener (the plot status, feature 011 slice 2) binds {@code tenantId} with {@code TenantContext.runAs} before it
 * opens its transaction and recomputes the debt of the plots from the committed ledger, so a redelivered event changes
 * nothing. Carries ids only, no personal data.
 *
 * @param tenantId the cooperative
 * @param plotIds  the plots whose ledger changed; never empty
 */
public record ChargesBooked(UUID tenantId, Set<UUID> plotIds) {

    public ChargesBooked {
        Objects.requireNonNull(tenantId, "tenantId");
        plotIds = Set.copyOf(plotIds);
        if (plotIds.isEmpty()) {
            throw new IllegalArgumentException("A booking names at least one plot");
        }
    }
}
