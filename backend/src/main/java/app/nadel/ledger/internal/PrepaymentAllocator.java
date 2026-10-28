package app.nadel.ledger.internal;

import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.PaymentsChanged;
import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Paired;
import app.nadel.ledger.internal.PlotLedgerQueries.PlotLedger;
import app.nadel.money.Money;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places a plot's prepayment on its open charges automatically after charges were booked for the plot (feature 015
 * §2.4, §2.7, technical considerations 2.4): a month run, a reading, a run, an opening balance, a recalculation, and
 * the reversals and adjustments whose released money ({@link AllocationReleaser}) became prepayment, since each of these
 * paths publishes {@link ChargesBooked} for its plots. Only when the cooperative's setting
 * {@link CooperativeSettings#autoAllocatePrepayment()} is on; otherwise the prepayment waits for the treasurer to
 * allocate it by hand ({@code PaymentCorrections}).
 * <p>
 * The ADR 0007 listener pattern, as {@link PlotStatusRefresher}: {@code @Async @TransactionalEventListener} after the
 * commit, without {@code @Transactional}; the event's cooperative is bound ({@link TenantContext#runAs}) before its own
 * transaction opens ({@link TransactionTemplate}). In that transaction: the setting (off: nothing); the plots of the
 * event that have a prepayment and an open charge, read without a lock (most plots of a month run have neither); their
 * plot ledger locks in one sorted call ({@link PlotLedgerLock}); their ledgers read again under the locks; per plot the
 * proposal of {@link AllocationPlanner#propose} for the prepayment over its open charges, each line's money taken from
 * the payments with money left, oldest first ({@link AllocationPlanner#pairWithPayments}), written as allocations of
 * origin {@code PREPAYMENT_AUTO} by the system ({@code created_by} null); then {@link PaymentsChanged} for the plots
 * that got allocations (at most {@value PaymentsChanged#MAX_PLOTS} per event), so their status is refreshed.
 * <p>
 * Idempotent: only the prepayment found under the plot ledger locks is placed, so a redelivered or late event writes
 * nothing more. It never publishes {@link ChargesBooked}, so it never triggers itself. Payment entry, reallocation and
 * manual allocation publish only {@link PaymentsChanged} and never trigger it: entry and reallocation place their own
 * remainder in their transaction while the setting is on ({@code PaymentService}, {@code PaymentCorrections}, origin
 * {@code PREPAYMENT_AUTO} too), so what a payment leaves waits here only when the plot owed nothing more. Automatic
 * allocations are ledger rows, not audit entries. Logs counts only.
 */
@Component
class PrepaymentAllocator {

    private static final Logger log = LoggerFactory.getLogger(PrepaymentAllocator.class);

    private final CooperativeSettings settings;
    private final PlotLedgerQueries ledgers;
    private final PlotLedgerLock plotLocks;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PrepaymentAllocator(CooperativeSettings settings, PlotLedgerQueries ledgers, PlotLedgerLock plotLocks,
            ApplicationEventPublisher events, EntityManager entityManager, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.ledgers = ledgers;
        this.plotLocks = plotLocks;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ChargesBooked event) {
        Integer plots = TenantContext.runAs(event.tenantId(),
                () -> transaction.execute(status -> allocate(event.tenantId(), event.plotIds())));
        if (plots != null && plots > 0) {
            log.debug("Prepayment of {} plots allocated automatically", plots);
        }
    }

    /** Places the prepayment of the plots; returns how many plots got allocations. */
    private int allocate(UUID tenantId, Set<UUID> plotIds) {
        if (!settings.autoAllocatePrepayment()) {
            return 0;
        }
        List<UUID> candidates = candidates(ledgers.load(plotIds).values());
        if (candidates.isEmpty()) {
            return 0;
        }
        plotLocks.lockAll(tenantId, candidates);
        Instant now = clock.instant();
        Set<UUID> allocated = new TreeSet<>();
        for (PlotLedger ledger : ledgers.load(candidates).values()) {
            PlotLedgerState state = ledger.state();
            List<Paired> paired = allocation(state);
            for (Paired part : paired) {
                entityManager.persist(AllocationRecord.allocate(tenantId, ledger.plotId(), part.paymentId(),
                        part.chargeId(), part.amount(), AllocationOrigin.PREPAYMENT_AUTO, null, now));
            }
            if (!paired.isEmpty()) {
                allocated.add(ledger.plotId());
            }
        }
        if (allocated.isEmpty()) {
            return 0;
        }
        entityManager.flush();
        List<UUID> changed = new ArrayList<>(allocated);
        for (int from = 0; from < changed.size(); from += PaymentsChanged.MAX_PLOTS) {
            events.publishEvent(new PaymentsChanged(tenantId,
                    Set.copyOf(changed.subList(from, Math.min(changed.size(), from + PaymentsChanged.MAX_PLOTS)))));
        }
        return allocated.size();
    }

    /** The plots, as read without a lock, that have a prepayment and an open charge, in id order. */
    private static List<UUID> candidates(Collection<PlotLedger> plots) {
        return plots.stream().filter(ledger -> available(ledger.state()).signum() > 0
                && !AllocationPlanner.openInOrder(ledger.state().charges()).isEmpty())
                .map(PlotLedger::plotId).sorted().toList();
    }

    /**
     * The allocations that place the plot's prepayment on its open charges in the planner's order, each named after a
     * payment with money left (oldest first); empty when there is nothing to place.
     */
    static List<Paired> allocation(PlotLedgerState state) {
        Money amount = available(state);
        if (amount.signum() == 0) {
            return List.of();
        }
        List<Line> lines = AllocationPlanner.propose(amount, state.charges()).lines();
        return lines.isEmpty() ? List.of() : AllocationPlanner.pairWithPayments(lines, state.payments());
    }

    /**
     * What can be placed: the plot's prepayment, and never more than its payments have left (they are the same on a
     * consistent ledger; the smaller keeps a pairing from running out of payments).
     */
    private static Money available(PlotLedgerState state) {
        Money left = state.payments().stream().map(PaymentState::left).filter(money -> money.signum() > 0)
                .reduce(Money.ZERO, Money::plus);
        return AllocationPlanner.min(state.prepayment(), left);
    }
}
