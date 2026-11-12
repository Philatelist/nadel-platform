package app.nadel.notifications.internal.push;

import app.nadel.documents.InvoicesIssued;
import app.nadel.notifications.internal.push.PushFanOut.Push;
import app.nadel.plots.PlotOwners;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * «Новий рахунок за {місяць}» (feature 021 §2.2): when invoices are issued ({@link InvoicesIssued}: a month build's
 * plots with a new version, or one invoice issued on request), every device of the current owners of those plots gets
 * one push {@code INVOICES_ISSUED} with the month, however many of the plots a member owns: one job {@link SendPush}
 * per device, with the build as its reference, so a redelivered event queues nothing new.
 * <p>
 * The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} without {@code @Transactional};
 * {@link PushFanOut} binds the event's cooperative, reads the owners ({@link PlotOwners#currentOwnerIds}) and their
 * devices in a short transaction and queues the jobs after it. Nothing while push is off.
 */
@Component
class InvoicePushes {

    private final PlotOwners owners;
    private final PushFanOut fanOut;

    InvoicePushes(PlotOwners owners, PushFanOut fanOut) {
        this.owners = owners;
        this.fanOut = fanOut;
    }

    @Async
    @TransactionalEventListener
    void on(InvoicesIssued event) {
        queue(event);
    }

    /** Queues the pushes of the event; how many jobs were handed to JobRunr. */
    int queue(InvoicesIssued event) {
        return fanOut.send(event.tenantId(), () -> {
            Set<UUID> members = new LinkedHashSet<>();
            owners.currentOwnerIds(event.plotIds()).values().forEach(members::addAll);
            return members.stream().map(member -> new Push(member, PushCode.INVOICES_ISSUED,
                    event.buildId().toString(), event.month().toString())).toList();
        });
    }
}
