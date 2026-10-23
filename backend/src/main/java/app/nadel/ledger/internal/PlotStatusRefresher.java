package app.nadel.ledger.internal;

import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.plots.PlotDebtCache;
import app.nadel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds the debt cache behind the plot status after charges were booked or reversed (feature 011 §2.10). The ADR
 * 0007 listener pattern: {@code @Async @TransactionalEventListener} without {@code @Transactional}, so the event's
 * cooperative is bound ({@link TenantContext#runAs}) before the transaction opens and the tenant-aware transaction
 * manager sets {@code app.tenant_id}. The Spring Modulith registry keeps the publication until this returns; a failure
 * leaves it incomplete and it is resubmitted.
 * <p>
 * Idempotent: the cache rows are locked first ({@link PlotDebtCache#lockAll}, {@code FOR UPDATE} in id order), then
 * the committed ledger of the plots is summed by {@link DebtCalculator} and recorded, so a redelivered or late event
 * writes the same values, and of two refreshes of a plot the later one reads at least what the earlier one read. Logs
 * ids only.
 */
@Component
class PlotStatusRefresher {

    private static final Logger log = LoggerFactory.getLogger(PlotStatusRefresher.class);

    private final ChargeRepository charges;
    private final PlotDebtCache debtCache;
    private final TransactionTemplate transaction;

    PlotStatusRefresher(ChargeRepository charges, PlotDebtCache debtCache,
            PlatformTransactionManager transactionManager) {
        this.charges = charges;
        this.debtCache = debtCache;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(ChargesBooked event) {
        TenantContext.runAs(event.tenantId(), () -> transaction.execute(status -> refresh(event)));
        log.debug("Debt of {} plots refreshed", event.plotIds().size());
    }

    private Void refresh(ChargesBooked event) {
        debtCache.lockAll(event.plotIds());
        Map<UUID, List<ChargeRecord>> byPlot = charges.findByPlotIdIn(event.plotIds()).stream()
                .collect(Collectors.groupingBy(ChargeRecord::getPlotId));
        for (UUID plotId : event.plotIds()) {
            Debts debts = DebtCalculator.of(byPlot.getOrDefault(plotId, List.of()).stream()
                    .map(DebtCalculator::line).toList());
            debtCache.record(plotId, debts.total(), debts.oldestAccrual());
        }
        return null;
    }
}
