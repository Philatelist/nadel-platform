package app.nadel.notifications.internal.push;

import app.nadel.documents.InvoicesIssued;
import app.nadel.notifications.internal.push.PushSubscriptions.Recipient;
import app.nadel.plots.PlotOwners;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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
 * «Новий рахунок за {місяць}» (feature 021 §2.2): when invoices are issued ({@link InvoicesIssued}: a month build's
 * plots with a new version, or one invoice issued on request), every device of the current owners of those plots gets
 * one push {@code INVOICES_ISSUED} with the month, however many of the plots a member owns: one job {@link SendPush}
 * per device, with the build as its reference, so a redelivered event queues nothing new.
 * <p>
 * The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} without {@code @Transactional}; the event's
 * cooperative is bound ({@link TenantContext#runAs}) before a short transaction reads the owners
 * ({@link PlotOwners#currentOwnerIds}) and their devices and deletes the devices made with another key pair; the jobs
 * are queued after it. Nothing while push is off. Logs carry the cooperative and counts only.
 */
@Component
class InvoicePushes {

    private static final Logger log = LoggerFactory.getLogger(InvoicePushes.class);

    private final VapidKeys keys;
    private final PlotOwners owners;
    private final PushSubscriptions subscriptions;
    private final PushQueue queue;
    private final Clock clock;
    private final TransactionTemplate transaction;

    InvoicePushes(VapidKeys keys, PlotOwners owners, PushSubscriptions subscriptions, PushQueue queue, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.owners = owners;
        this.subscriptions = subscriptions;
        this.queue = queue;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(InvoicesIssued event) {
        queue(event);
    }

    /** Queues the pushes of the event; how many jobs were handed to JobRunr. */
    int queue(InvoicesIssued event) {
        if (!keys.enabled()) {
            return 0;
        }
        return TenantContext.runAs(event.tenantId(), () -> {
            byte[] serverKeyId = keys.serverKeyId();
            List<Recipient> devices = transaction.execute(status -> {
                Set<UUID> members = new LinkedHashSet<>();
                owners.currentOwnerIds(event.plotIds()).values().forEach(members::addAll);
                List<Recipient> found = subscriptions.devicesOf(members);
                found.stream().filter(device -> !Arrays.equals(device.serverKeyId(), serverKeyId))
                        .forEach(device -> subscriptions.delete(device.id()));
                return found.stream().filter(device -> Arrays.equals(device.serverKeyId(), serverKeyId)).toList();
            });
            Instant now = clock.instant();
            int queued = 0;
            for (Recipient device : devices == null ? List.<Recipient>of() : devices) {
                SendPush job = new SendPush(event.tenantId(), device.id(), PushCode.INVOICES_ISSUED.name(),
                        event.buildId().toString(), event.month().toString(), 0, now);
                if (queue.enqueue(job)) {
                    queued++;
                }
            }
            log.info("Push INVOICES_ISSUED of cooperative {}: {} devices", event.tenantId(), queued);
            return queued;
        });
    }
}
