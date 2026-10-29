package app.nadel.meters;

import java.util.Objects;
import java.util.UUID;

/**
 * The board approved or rejected a reading a member submitted (feature 017 §2.5: the bot tells the member). Published
 * inside the transaction of the decision, only for a reading submitted by a member (source {@code MEMBER}, with its
 * submitter). Ids and a code only: never the value, the serial number or the reason of a rejection (user content), which
 * a listener reads back through {@link MemberReadings#decision} after binding {@code tenantId} with
 * {@code TenantContext.runAs} (ADR 0007).
 *
 * @param tenantId          the cooperative
 * @param readingId         the decided reading
 * @param plotId            the plot of its meter
 * @param submitterMemberId the member who submitted it
 * @param status            {@link ReadingStatus#APPROVED} or {@link ReadingStatus#REJECTED}
 */
public record ReadingDecided(UUID tenantId, UUID readingId, UUID plotId, UUID submitterMemberId,
        ReadingStatus status) {

    public ReadingDecided {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(readingId, "readingId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(submitterMemberId, "submitterMemberId");
        Objects.requireNonNull(status, "status");
        if (status != ReadingStatus.APPROVED && status != ReadingStatus.REJECTED) {
            throw new IllegalArgumentException("A decision approves or rejects");
        }
    }
}
