package app.nadel.ledger.internal;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-cooperative charging lock (feature 012, technical considerations 2.2): a transaction-scoped advisory lock
 * {@code ledger.charging:{tenant}} that serialises every path of the cooperative that books or reverses charges. It is
 * taken <b>first</b>, before any other ledger lock, by the month run booking, the retry of a "not charged" item, the
 * charges of readings ({@link ReadingCharges}), the booking and the reversal of losses and common consumer runs, a
 * reversal by hand ({@link Reversals}) and a recalculation (its preview too). Nobody takes it while holding another
 * ledger lock, so it cannot take part in a deadlock; a second path of the cooperative waits until the first one ends.
 * Postgres advisory locks are reentrant within a transaction, so a path that takes it twice does not block itself.
 */
@Component
class ChargingLock {

    private static final String KEY = "ledger.charging:";

    private final EntityManager entityManager;

    ChargingLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Waits for the cooperative's charging lock and holds it until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    void lock(UUID tenantId) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", KEY + tenantId)
                .getSingleResult();
    }
}
