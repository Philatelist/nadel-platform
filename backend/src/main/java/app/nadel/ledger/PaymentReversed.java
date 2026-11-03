package app.nadel.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payments of one cooperative were reversed (feature 017 §2.4: the reversal messages to the plot's owners), published
 * inside the transaction that wrote the reversal, next to {@link PaymentsChanged}. It names the reversed payments (kind
 * {@code PAYMENT}), not their reversal rows, so {@link LedgerReadModel#paymentsByIds} answers each with
 * {@link PaymentRow#reversed} set, the day it was received and its amount. A listener binds {@code tenantId} with
 * {@code TenantContext.runAs} before it opens its transaction. Carries ids only: no amounts, reasons, names or texts
 * (the outbox has no RLS).
 *
 * @param tenantId   the cooperative
 * @param paymentIds the payments that were reversed; never empty
 */
public record PaymentReversed(UUID tenantId, List<UUID> paymentIds) {

    public PaymentReversed {
        Objects.requireNonNull(tenantId, "tenantId");
        paymentIds = List.copyOf(paymentIds);
        if (paymentIds.isEmpty()) {
            throw new IllegalArgumentException("A reversed payment event names at least one payment");
        }
    }
}
