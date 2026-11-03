package app.nadel.notifications.internal;

import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The message of an announcement's delivery (feature 017 §2.6), written when it is sent: the text exactly as the board
 * entered it (Telegram sends it as a plain message without formatting; the letter carries it as plain text and as
 * escaped HTML, see {@link MailChannel}) and the letter's subject «Оголошення правління {cooperative}» from
 * {@code messages_uk.properties}. No PDF, no button. Reads in the caller's transaction of the bound cooperative; never
 * logs the text.
 */
@Component
class AnnouncementMessages {

    private final AnnouncementRows announcements;
    private final TenantDirectory tenants;

    AnnouncementMessages(AnnouncementRows announcements, TenantDirectory tenants) {
        this.announcements = announcements;
        this.tenants = tenants;
    }

    /** The message of the announcement; empty when it does not exist. */
    Optional<DeliveryMessage> of(UUID announcementId) {
        return announcements.find(announcementId).filter(found -> found.text() != null).map(found -> {
            String sender = tenants.current().map(TenantSummary::name).orElse("");
            String subject = NotificationTexts.uk().text("announcement.email.subject", Map.of("cooperative", sender));
            return new DeliveryMessage(subject, found.text(), null, null, null, null, sender);
        });
    }
}
