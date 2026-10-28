package app.nadel.ledger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The payer notes of payments (feature 015 §6: the payer note column of the payments report). A note is personal data
 * (ADR 0016): it is stored sealed and this port is the only way out of the ledger for it, a documented exception to the
 * rule that {@link LedgerReadModel} carries no personal data. The implementation requires {@code MEMBERS_VIEW}
 * ({@code @PreAuthorize}: a caller without it is refused with an {@code AccessDeniedException}) and runs in the
 * caller's transaction ({@code Propagation.MANDATORY}), bound to the cooperative, so a payment of another cooperative
 * is never found. Never log what it returns.
 */
public interface PaymentNotes {

    /**
     * The opened payer note of each payment asked that has one; a payment without a note, or an id unknown in the
     * cooperative, is absent. Asked 500 per query.
     */
    Map<UUID, String> notes(Collection<UUID> paymentIds);
}
