package app.nadel.notifications.internal;

import app.nadel.members.TelegramLinkStatus;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serves {@code members.TelegramLinkStatus}: the «Telegram: підключено» flag of the member card, never more. */
@Component
class TelegramLinkStatusProvider implements TelegramLinkStatus {

    private final TelegramLinks links;

    TelegramLinkStatusProvider(TelegramLinks links) {
        this.links = links;
    }

    @Override
    public Set<UUID> linkedAmong(Collection<UUID> memberIds) {
        return links.linkedAmong(memberIds);
    }
}
