package app.nadel.members.internal;

import app.nadel.members.TelegramLinkStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Whether a member's Telegram is linked, for the member card (feature 017 §2.2), through the SPI
 * {@link TelegramLinkStatus} that {@code notifications} implements. Without an implementation no member is linked.
 */
@Component
class TelegramLinkFlags {

    private static final TelegramLinkStatus NONE = ids -> Set.of();

    private final ObjectProvider<TelegramLinkStatus> status;

    TelegramLinkFlags(ObjectProvider<TelegramLinkStatus> status) {
        this.status = status;
    }

    /** Runs in the caller's transaction (the cooperative is bound). */
    boolean linked(UUID memberId) {
        return status.getIfAvailable(() -> NONE).linkedAmong(List.of(memberId)).contains(memberId);
    }
}
