package app.nadel.notifications.internal;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The channel each member gets documents through now ({@link ChannelResolver}), from their preferred channel, their
 * e-mail, their Telegram link and the cooperative's settings. Runs in the caller's transaction of the bound cooperative;
 * the contacts it reads are never kept or logged.
 */
@Component
class RecipientChannels {

    private final MemberDirectory members;
    private final TelegramLinks links;

    RecipientChannels(MemberDirectory members, TelegramLinks links) {
        this.members = members;
        this.links = links;
    }

    /** The channel of each given member that exists in the cooperative, in the order given. */
    Map<UUID, Resolution> resolve(Collection<UUID> memberIds, DeliverySettingsValues settings) {
        List<UUID> ids = memberIds.stream().distinct().toList();
        Map<UUID, Resolution> resolved = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return resolved;
        }
        Map<UUID, DeliveryChannel> preferred = members.deliveryChannels(ids);
        Map<UUID, MemberContacts> contacts = members.contacts(ids);
        Set<UUID> linked = links.linkedAmong(ids);
        for (UUID id : ids) {
            DeliveryChannel channel = preferred.get(id);
            if (channel == null) {
                continue;
            }
            MemberContacts contact = contacts.get(id);
            boolean hasEmail = contact != null && contact.email() != null && !contact.email().isBlank();
            resolved.put(id, ChannelResolver.resolve(channel, hasEmail, linked.contains(id),
                    settings.emailConfigured(), settings.botConfigured()));
        }
        return resolved;
    }
}
