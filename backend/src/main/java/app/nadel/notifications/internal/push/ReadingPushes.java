package app.nadel.notifications.internal.push;

import app.nadel.identity.Permission;
import app.nadel.members.RoleHolders;
import app.nadel.meters.ReadingDecided;
import app.nadel.meters.ReadingStatus;
import app.nadel.meters.ReadingSubmitted;
import app.nadel.notifications.internal.push.PushFanOut.Push;
import java.util.List;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The pushes about readings (feature 021 §2.2), the ADR 0007 listener pattern through {@link PushFanOut}:
 * <ul>
 *   <li>{@link ReadingDecided}: the member who submitted the reading gets «Показ лічильника прийнято»
 *       ({@code READING_APPROVED}) or «Показ лічильника відхилено» ({@code READING_REJECTED}) on every device, with
 *       the reading as the reference;</li>
 *   <li>{@link ReadingSubmitted} (a member's own submission): every active member who holds {@code READINGS_APPROVE}
 *       ({@link RoleHolders#activeWithPermission}, the chairman included), except the submitter, gets «Нові покази на
 *       перевірку» ({@code READINGS_TO_APPROVE}), at most once in a rolling hour ({@link PushThrottle}), with a time
 *       to live of an hour and the {@code Topic} {@code readings-approvals}.</li>
 * </ul>
 * A redelivered event queues nothing new: the job ids repeat, and the throttle refuses a second claim in the hour.
 */
@Component
class ReadingPushes {

    private final RoleHolders roleHolders;
    private final PushFanOut fanOut;

    ReadingPushes(RoleHolders roleHolders, PushFanOut fanOut) {
        this.roleHolders = roleHolders;
        this.fanOut = fanOut;
    }

    @Async
    @TransactionalEventListener
    void on(ReadingDecided event) {
        queue(event);
    }

    @Async
    @TransactionalEventListener
    void on(ReadingSubmitted event) {
        queue(event);
    }

    /** Queues the push of the decision; how many jobs were handed to JobRunr. */
    int queue(ReadingDecided event) {
        PushCode code = event.status() == ReadingStatus.APPROVED ? PushCode.READING_APPROVED
                : PushCode.READING_REJECTED;
        return fanOut.send(event.tenantId(), () -> List.of(new Push(event.submitterMemberId(), code,
                event.readingId().toString(), null)));
    }

    /** Queues the pushes of the submission to the approvers; how many jobs were handed to JobRunr. */
    int queue(ReadingSubmitted event) {
        return fanOut.send(event.tenantId(), () -> roleHolders.activeWithPermission(Permission.READINGS_APPROVE)
                .stream()
                .filter(member -> !member.equals(event.submitterMemberId()))
                .map(member -> new Push(member, PushCode.READINGS_TO_APPROVE, event.readingId().toString(), null))
                .toList());
    }
}
