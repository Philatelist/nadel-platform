package app.nadel.ledger.internal;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The per-plot ledger lock (feature 015, technical considerations 2.4): a transaction-scoped advisory lock
 * {@code ledger.plot:{tenant}:{plot}} that serialises every path that places money on a plot's charges or takes it
 * back (a payment, and from later slices a reversal, a reallocation, a prepayment allocation, a charge reversal or
 * adjustment that releases allocations). It plays the role of "the plot locked {@code FOR UPDATE} in the ledger"
 * without touching the plots module's row, which booking paths already hold {@code FOR SHARE}.
 * <p>
 * Lock order: charging lock, then network / queue / charge type / tariff {@code FOR SHARE}, then plot {@code FOR SHARE},
 * then <b>these locks</b>, then the billing month {@code FOR SHARE}, then the {@code receipt_counter} row. The plots of
 * one call are locked in one sorted order (the text of the id, which is also PostgreSQL's uuid order), so two paths that
 * lock overlapping sets never wait for each other in a circle; a path locks all its plots in one call. Advisory locks
 * are reentrant within a transaction. The plots locked by the transaction are remembered in a transaction-bound set, so
 * a path that must run under the lock can check it ({@link #assertHeld}).
 */
@Component
class PlotLedgerLock {

    private static final String KEY = "ledger.plot:";

    /** The resource key of the transaction-bound set of locked plots. */
    private static final Object HELD = new Object();

    private final EntityManager entityManager;

    PlotLedgerLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Waits for the ledger locks of the plots, in sorted order, and holds them until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockAll(UUID tenantId, Collection<UUID> plotIds) {
        Set<UUID> held = held();
        List<UUID> sorted = plotIds.stream().distinct().sorted((a, b) -> a.toString().compareTo(b.toString())).toList();
        for (UUID plotId : sorted) {
            if (held.contains(plotId)) {
                continue;
            }
            entityManager.createNativeQuery(
                    "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                    .setParameter("key", KEY + tenantId + ":" + plotId)
                    .getSingleResult();
            held.add(plotId);
        }
    }

    /**
     * Fails unless this transaction holds the ledger locks of every one of the plots.
     *
     * @throws IllegalStateException when a plot is not locked: a path forgot {@link #lockAll}, a bug
     */
    void assertHeld(Collection<UUID> plotIds) {
        Set<UUID> held = TransactionSynchronizationManager.isSynchronizationActive() ? held() : Set.of();
        if (!held.containsAll(plotIds)) {
            throw new IllegalStateException("The plot ledger locks are not held");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> held() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("The plot ledger lock needs a transaction");
        }
        Set<UUID> held = (Set<UUID>) TransactionSynchronizationManager.getResource(HELD);
        if (held == null) {
            Set<UUID> fresh = new HashSet<>();
            TransactionSynchronizationManager.bindResource(HELD, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // an inner REQUIRES_NEW transaction holds no lock of this one: the set leaves with the suspension
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(HELD);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(HELD, fresh);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(HELD);
                }
            });
            held = fresh;
        }
        return held;
    }
}
