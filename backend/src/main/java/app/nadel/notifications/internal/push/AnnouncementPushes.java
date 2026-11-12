package app.nadel.notifications.internal.push;

import app.nadel.notifications.internal.AnnouncementSent;
import app.nadel.notifications.internal.DeliveryRows;
import app.nadel.notifications.internal.push.PushFanOut.Push;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * «Нове оголошення від правління» (feature 021 §2.2): when the board sends an announcement ({@link AnnouncementSent}),
 * every member with a delivery row of it ({@link DeliveryRows#announcementRecipients}: e-mail, Telegram and paper
 * alike, so a member on paper with push on also learns of it) gets one push {@code ANNOUNCEMENT} on every device, with
 * the announcement as the reference. The ADR 0007 listener pattern through {@link PushFanOut}; a redelivered event
 * queues nothing new.
 */
@Component
class AnnouncementPushes {

    private final DeliveryRows rows;
    private final PushFanOut fanOut;

    AnnouncementPushes(DeliveryRows rows, PushFanOut fanOut) {
        this.rows = rows;
        this.fanOut = fanOut;
    }

    @Async
    @TransactionalEventListener
    void on(AnnouncementSent event) {
        queue(event);
    }

    /** Queues the pushes of the announcement; how many jobs were handed to JobRunr. */
    int queue(AnnouncementSent event) {
        return fanOut.send(event.tenantId(), () -> rows.announcementRecipients(event.announcementId()).stream()
                .map(member -> new Push(member, PushCode.ANNOUNCEMENT, event.announcementId().toString(), null))
                .toList());
    }
}
