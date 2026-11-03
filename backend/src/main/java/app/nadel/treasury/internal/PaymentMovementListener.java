package app.nadel.treasury.internal;

import app.nadel.ledger.PaymentMoneyMoved;
import app.nadel.tenancy.TenantContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the journal movements of member payments and their reversals (feature 018 §2.2, technical considerations 2.2,
 * ADR 0019) <b>in the payment's own transaction</b>. A plain synchronous {@link EventListener}: Spring Modulith stores
 * publications only for transactional listeners, so {@link PaymentMoneyMoved} never reaches {@code event_publication},
 * the movement commits with the payment or not at all, and an exception here rolls the payment back. It must never
 * become an {@code @ApplicationModuleListener} or a {@code @TransactionalEventListener} (guarded by
 * {@code PaymentMovementsTests}). This is the one exception to "cross-module side effects through the outbox": the
 * movement is the same money fact as the payment. The work is idempotent ({@link MovementWriter#writePaymentLines}:
 * {@code ON CONFLICT DO NOTHING} on the payment row).
 */
@Component
class PaymentMovementListener {

    private final MovementWriter writer;

    PaymentMovementListener(MovementWriter writer) {
        this.writer = writer;
    }

    /**
     * Writes the event's movements in the publishing transaction ({@code MANDATORY} on the writer: a publication
     * outside a transaction fails).
     *
     * @throws IllegalStateException when the event's cooperative is not the one bound to the transaction
     */
    @EventListener
    void on(PaymentMoneyMoved event) {
        if (!TenantContext.currentTenantId().map(event.tenantId()::equals).orElse(false)) {
            throw new IllegalStateException("The payment event is not of the bound cooperative");
        }
        writer.writePaymentLines(event.tenantId(), event.lines());
    }
}
