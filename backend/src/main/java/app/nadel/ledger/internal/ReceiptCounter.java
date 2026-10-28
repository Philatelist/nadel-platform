package app.nadel.ledger.internal;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out the cooperative's cash receipt numbers (feature 015 §2.3, technical considerations 2.5) from table
 * {@code receipt_counter}: inside the saving transaction, after the plot ledger locks and the billing month, the row is
 * created at 0 when missing and moved forward by one. The row lock of the {@code UPDATE} is held until the transaction
 * ends, so a concurrent save of the cooperative waits and then gets the next number (no duplicates), and a rollback
 * undoes the step (no gaps). A trigger refuses any other change than {@code +1}.
 */
@Component
class ReceiptCounter {

    private final EntityManager entityManager;

    ReceiptCounter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The next receipt number of the cooperative, taken in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    long next(UUID tenantId) {
        entityManager.createNativeQuery("insert into receipt_counter (tenant_id, last_number) values (:tenant, 0) "
                + "on conflict (tenant_id) do nothing")
                .setParameter("tenant", tenantId)
                .executeUpdate();
        Object number = entityManager.createNativeQuery("with step as (update receipt_counter "
                + "set last_number = last_number + 1 where tenant_id = :tenant returning last_number) "
                + "select last_number from step")
                .setParameter("tenant", tenantId)
                .getSingleResult();
        return ((Number) number).longValue();
    }
}
