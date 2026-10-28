package app.nadel.ledger.internal;

import app.nadel.ledger.internal.ReleasePlanner.Release;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes money back from charges (feature 015 §2.3, technical considerations 2.4): the one place that writes release
 * rows. Every path that reverses or lowers a charge, or reverses or reallocates a payment, calls it in its own
 * transaction, after it took the plot ledger locks of the plots ({@link PlotLedgerLock#lockAll}) and before it commits;
 * the deferred database backstops of V27 refuse a commit that leaves a charge or a reversed payment with more
 * allocated than it holds.
 * <ul>
 * <li>{@link #settleCharges}: after a charge path wrote its reversals or adjustments, releases what the charges of the
 * plots no longer hold ({@link ReleasePlanner#forCharges}: everything on a reversed charge, the excess of a lowered one
 * from its latest allocations).</li>
 * <li>{@link #releasePayment}: releases every live allocation of one payment ({@link ReleasePlanner#forPayment}),
 * before its reversal row or its new allocations.</li>
 * </ul>
 * Both assert the locks ({@link PlotLedgerLock#assertHeld}), read the ledger of the plots again under them (with what
 * the transaction wrote so far: the persistence context is flushed first), write the releases and flush them, so a
 * row written next by the caller comes after them. They return the releases written (empty when nothing was on the
 * charges). The money released by a charge path becomes the plot's prepayment; the money of a reversed payment is gone
 * with it. Logs nothing: the releases are ledger rows, not audit entries.
 */
@Component
class AllocationReleaser {

    private final PlotLedgerLock plotLocks;
    private final PlotLedgerQueries ledgers;
    private final EntityManager entityManager;
    private final Clock clock;

    AllocationReleaser(PlotLedgerLock plotLocks, PlotLedgerQueries ledgers, EntityManager entityManager, Clock clock) {
        this.plotLocks = plotLocks;
        this.ledgers = ledgers;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Releases what the charges of the plots no longer hold after a reversal or an adjustment written in this
     * transaction.
     *
     * @param createdBy the member whose action caused it; null for the system
     * @throws IllegalStateException when the plot ledger locks of the plots are not held
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Release> settleCharges(UUID tenantId, Collection<UUID> plotIds, @Nullable UUID createdBy) {
        plotLocks.assertHeld(plotIds);
        entityManager.flush();
        List<Release> releases = new ArrayList<>();
        ledgers.load(plotIds).values().forEach(ledger -> releases.addAll(ReleasePlanner.forCharges(ledger.state())));
        return write(tenantId, releases, createdBy);
    }

    /**
     * Releases every live allocation of the payment of the plot, because it is reversed ({@code PAYMENT_REVERSED})
     * or reallocated ({@code REALLOCATED}).
     *
     * @param createdBy the member whose action caused it; null for the system
     * @throws IllegalStateException    when the plot ledger lock of the plot is not held
     * @throws IllegalArgumentException for another cause
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Release> releasePayment(UUID tenantId, UUID plotId, UUID paymentId, ReleaseCause cause,
            @Nullable UUID createdBy) {
        plotLocks.assertHeld(Set.of(plotId));
        entityManager.flush();
        PlotLedgerState state = ledgers.load(Set.of(plotId)).get(plotId).state();
        return write(tenantId, ReleasePlanner.forPayment(paymentId, state.allocations(), cause), createdBy);
    }

    private List<Release> write(UUID tenantId, List<Release> releases, @Nullable UUID createdBy) {
        if (releases.isEmpty()) {
            return releases;
        }
        Instant now = clock.instant();
        releases.forEach(release -> entityManager.persist(AllocationRecord.releaseOf(tenantId, release, createdBy,
                now)));
        entityManager.flush();
        return List.copyOf(releases);
    }
}
