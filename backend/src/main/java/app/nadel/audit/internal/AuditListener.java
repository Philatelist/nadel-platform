package app.nadel.audit.internal;

import app.nadel.audit.AuditEvent;
import app.nadel.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the audit entry after the publishing transaction has committed (option A of the design, see README).
 * <p>
 * The method is deliberately not {@code @Transactional}: a transactional listener would begin its transaction
 * before the body runs, when the tenant of this thread is still unknown. The body binds the event's cooperative
 * first and only then opens the transaction, so the tenant-aware transaction manager sets {@code app.tenant_id}.
 * A failure leaves the publication incomplete in the event publication registry, and it is resubmitted.
 * <p>
 * Logs ids only, never the event payload (it holds names and contact details).
 */
@Component
class AuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuditListener.class);

    private final AuditWriter writer;
    private final TransactionTemplate transaction;

    AuditListener(AuditWriter writer, PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(AuditEvent event) {
        boolean inserted = TenantContext.runAs(event.tenantId(),
                () -> Boolean.TRUE.equals(transaction.execute(status -> writer.write(event))));
        if (!inserted) {
            log.debug("Audit event {} was already recorded", event.eventId());
        }
    }
}
