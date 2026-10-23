package app.nadel.ledger.internal;

import app.nadel.meters.PlotMeterPointsChanged;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
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
 * Charges the consumption of plot meters when their points change (feature 011 §2.4): an approved reading (entered by
 * the board, a member's reading approved), a correction or deletion, a removal or replacement. The ADR 0007 listener
 * pattern: {@code @Async @TransactionalEventListener} without {@code @Transactional}, so the event's cooperative is bound
 * ({@link TenantContext#runAs}) before the transaction opens and the tenant-aware transaction manager sets
 * {@code app.tenant_id}. The Spring Modulith registry keeps the publication until this returns; a failure leaves it
 * incomplete and it is resubmitted.
 * <p>
 * Idempotent: {@link IntervalCharging#chargeAround} books an interval only when no charge has its source key, so a
 * redelivered event writes nothing new. Business failures (no tariff for every day, conflicting tariffs, outside the
 * scope) never throw: they become "not charged" items. Charges from readings need no permission of their own; they
 * follow the reading change, and are booked without an author. Logs ids and counts only.
 */
@Component
class ReadingCharges {

    private static final Logger log = LoggerFactory.getLogger(ReadingCharges.class);

    private final IntervalCharging charging;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ReadingCharges(IntervalCharging charging, PlatformTransactionManager transactionManager, Clock clock) {
        this.charging = charging;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(PlotMeterPointsChanged event) {
        Set<UUID> booked = TenantContext.runAs(event.tenantId(), () -> transaction.execute(
                status -> charging.chargeAround(event.tenantId(), event.meterId(), event.on(), clock.instant())));
        log.debug("Meter {} points changed ({}): {} plots charged", event.meterId(), event.cause(),
                booked == null ? 0 : booked.size());
    }
}
