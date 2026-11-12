package app.nadel.notifications.internal;

import java.util.Objects;
import java.util.UUID;

/**
 * The board sent an announcement (feature 021 §2.2: its recipients get the push «Нове оголошення від правління»).
 * Published by {@link Announcements} in the transaction that wrote the announcement and its delivery rows; internal to
 * the module (public only so that {@code internal.push} can listen to it). Ids only: never the text. The listener binds
 * {@code tenantId} with {@code TenantContext.runAs} and reads the recipients back from the delivery rows
 * ({@link DeliveryRows#announcementRecipients}).
 *
 * @param tenantId       the cooperative
 * @param announcementId the announcement
 */
public record AnnouncementSent(UUID tenantId, UUID announcementId) {

    public AnnouncementSent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(announcementId, "announcementId");
    }
}
