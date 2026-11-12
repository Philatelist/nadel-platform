package app.nadel.notifications.internal.push;

import app.nadel.members.MemberAccessRevoked;
import app.nadel.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leaving the cooperative removes the member's devices (feature 021 §2.2): when the member's access is switched off
 * ({@link MemberAccessRevoked}: the board disabled it or archived the member), every device of the member is deleted,
 * so none receives anything further; a job already queued for one finds no device and sends nothing. Also while push
 * is off. The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener}, the event's cooperative bound
 * ({@link TenantContext#runAs}) before a short transaction. Idempotent: a redelivered event finds nothing left to
 * delete. Logs carry the cooperative, the member and the count only.
 */
@Component
class RevokedAccessDevices {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessDevices.class);

    private final PushSubscriptions subscriptions;
    private final TransactionTemplate transaction;

    RevokedAccessDevices(PushSubscriptions subscriptions, PlatformTransactionManager transactionManager) {
        this.subscriptions = subscriptions;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(MemberAccessRevoked event) {
        remove(event);
    }

    /** Deletes the member's devices; how many there were. */
    int remove(MemberAccessRevoked event) {
        Integer deleted = TenantContext.runAs(event.tenantId(), () -> transaction.execute(status ->
                subscriptions.deleteAllOf(event.memberId())));
        int count = deleted == null ? 0 : deleted;
        if (count > 0) {
            log.info("Push devices of member {} of cooperative {} removed: {}", event.memberId(), event.tenantId(),
                    count);
        }
        return count;
    }
}
