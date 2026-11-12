package app.nadel.meters;

import java.util.Objects;
import java.util.UUID;

/**
 * A member submitted a reading for the board's approval (feature 021 §2.2: the users who approve readings are told that
 * readings wait). Published inside the transaction of the submission, only for a member's own submission (the account,
 * with or without a photo, and the cooperative's Telegram bot), never for the board's entry, and not when the same
 * value again changes nothing. Ids only: never the value or the serial number (a listener has nothing to read back).
 * A listener binds {@code tenantId} with {@code TenantContext.runAs} before it opens its transaction (ADR 0007).
 *
 * @param tenantId          the cooperative
 * @param readingId         the new pending reading
 * @param plotId            the plot of its meter
 * @param submitterMemberId the member who submitted it
 */
public record ReadingSubmitted(UUID tenantId, UUID readingId, UUID plotId, UUID submitterMemberId) {

    public ReadingSubmitted {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(readingId, "readingId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(submitterMemberId, "submitterMemberId");
    }
}
