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
import java.util.function.Function;
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
        return resolve(memberIds, facts -> ChannelResolver.resolve(facts.preferred(), facts.hasEmail(),
                facts.linked(), settings.emailConfigured(), settings.botConfigured()));
    }

    /**
     * The channel of each given member that exists in the cooperative for an announcement through the {@code chosen}
     * channels ({@link ChannelResolver#forAnnouncement}), in the order given.
     */
    Map<UUID, Resolution> resolveForAnnouncement(Collection<UUID> memberIds, DeliverySettingsValues settings,
            Set<Channel> chosen) {
        return resolve(memberIds, facts -> ChannelResolver.forAnnouncement(facts.preferred(), facts.hasEmail(),
                facts.linked(), settings.emailConfigured(), settings.botConfigured(), chosen));
    }

    /** What decides a member's channel. */
    private record Facts(DeliveryChannel preferred, boolean hasEmail, boolean linked) {
    }

    private Map<UUID, Resolution> resolve(Collection<UUID> memberIds, Function<Facts, Resolution> rule) {
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
            resolved.put(id, rule.apply(new Facts(channel, hasEmail, linked.contains(id))));
        }
        return resolved;
    }
}
