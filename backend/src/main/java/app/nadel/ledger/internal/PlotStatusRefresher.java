package app.nadel.ledger.internal;

import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.PaymentsChanged;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.PlotLedgerQueries.PlotLedger;
import app.nadel.plots.PlotDebtCache;
import app.nadel.tenancy.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds the debt cache behind the plot status after charges were booked or reversed (feature 011 §2.10) and after
 * payments or allocations changed (feature 015 §2.5): the debt after payments, the oldest unpaid charge and the
 * prepayment. The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} without
 * {@code @Transactional}, so the event's cooperative is bound ({@link TenantContext#runAs}) before the transaction
 * opens and the tenant-aware transaction manager sets {@code app.tenant_id}. The Spring Modulith registry keeps the
 * publication until this returns; a failure leaves it incomplete and it is resubmitted.
 * <p>
 * Idempotent: the cache rows are locked first ({@link PlotDebtCache#lockAll}, {@code FOR UPDATE} in id order), then
 * the committed ledger of the plots (charges, allocations, payments: {@link PlotLedgerQueries}) is summed by
 * {@link DebtCalculator} and recorded, so a redelivered or late event writes the same values, and of two refreshes of a
 * plot the later one reads at least what the earlier one read. Logs ids only.
 */
@Component
class PlotStatusRefresher {

    private static final Logger log = LoggerFactory.getLogger(PlotStatusRefresher.class);

    private final PlotLedgerQueries ledgers;
    private final PlotDebtCache debtCache;
    private final TransactionTemplate transaction;

    PlotStatusRefresher(PlotLedgerQueries ledgers, PlotDebtCache debtCache,
            PlatformTransactionManager transactionManager) {
        this.ledgers = ledgers;
        this.debtCache = debtCache;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(ChargesBooked event) {
        refresh(event.tenantId(), event.plotIds());
    }

    @Async
    @TransactionalEventListener
    void on(PaymentsChanged event) {
        refresh(event.tenantId(), event.plotIds());
    }

    private void refresh(UUID tenantId, Set<UUID> plotIds) {
        TenantContext.runAs(tenantId, () -> transaction.execute(status -> record(plotIds)));
        log.debug("Debt of {} plots refreshed", plotIds.size());
    }

    private Void record(Set<UUID> plotIds) {
        debtCache.lockAll(plotIds);
        Map<UUID, PlotLedger> byPlot = ledgers.load(plotIds);
        byPlot.forEach((plotId, ledger) -> {
            Debts debts = DebtCalculator.of(ledger.charges(), ledger.allocations(), ledger.payments());
            debtCache.record(plotId, debts.total(), debts.oldestAccrual(), debts.prepayment());
        });
        return null;
    }
}
