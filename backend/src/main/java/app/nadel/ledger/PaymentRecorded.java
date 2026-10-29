package app.nadel.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payments were entered in one cooperative (feature 017 §2.4: the payment messages to the plot's owners): one event per
 * save, published inside the transaction that wrote them, next to {@link PaymentsChanged}. A payment of one plot names
 * its payment; a split names one payment per part above zero (each part is a payment of its own plot). A listener binds
 * {@code tenantId} with {@code TenantContext.runAs} before it opens its transaction and reads the payments through
 * {@link LedgerReadModel#paymentsByIds}, so a redelivered event reads the same committed rows. Carries ids only: no
 * amounts, names or texts (the outbox has no RLS).
 *
 * @param tenantId   the cooperative
 * @param paymentIds the saved payments (kind {@code PAYMENT}); never empty
 */
public record PaymentRecorded(UUID tenantId, List<UUID> paymentIds) {

    public PaymentRecorded {
        Objects.requireNonNull(tenantId, "tenantId");
        paymentIds = List.copyOf(paymentIds);
        if (paymentIds.isEmpty()) {
            throw new IllegalArgumentException("A recorded payment event names at least one payment");
        }
    }
}
