package app.nadel.members.internal;

import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serves the public {@link MemberDirectory} from the member table. */
@Component
class JpaMemberDirectory implements MemberDirectory {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    private final MemberRepository members;
    private final MemberCrypto crypto;

    JpaMemberDirectory(MemberRepository members, MemberCrypto crypto) {
        this.members = members;
        this.crypto = crypto;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, MemberRef> find(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return members.findAllById(ids).stream()
                .map(member -> new MemberRef(member.getId(), member.getLastName(), member.getFirstName(),
                        member.getMiddleName(), member.isArchived()))
                .collect(Collectors.toMap(MemberRef::id, Function.identity()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> activeIds() {
        return members.findActiveIds();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, MemberRef> lockActive(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return members.lockAllById(ids).stream()
                .map(member -> new MemberRef(member.getId(), member.getLastName(), member.getFirstName(),
                        member.getMiddleName(), member.isArchived()))
                .collect(Collectors.toMap(MemberRef::id, Function.identity()));
    }

    /**
     * Opens the phones and the e-mail of each member with the key of the caller's cooperative (the cipher refuses any
     * other than {@code TenantContext}'s, and RLS hides other cooperatives' members anyway). A value that does not open
     * fails the call ({@code PERSONAL_DATA_UNREADABLE}), never returns an empty contact.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, MemberContacts> contacts(Collection<UUID> ids) {
        List<UUID> distinct = ids.stream().distinct().toList();
        List<Member> found = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += CHUNK) {
            found.addAll(members.findAllById(distinct.subList(from, Math.min(distinct.size(), from + CHUNK))));
        }
        return found.stream()
                .map(member -> new MemberContacts(member.getId(), member.getLastName(), member.getFirstName(),
                        member.getMiddleName(), crypto.phones(member), crypto.email(member), member.isArchived()))
                .collect(Collectors.toMap(MemberContacts::id, Function.identity()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, app.nadel.members.DeliveryChannel> deliveryChannels(Collection<UUID> ids) {
        List<UUID> distinct = ids.stream().distinct().toList();
        Map<UUID, app.nadel.members.DeliveryChannel> channels = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += CHUNK) {
            for (Object[] row : members.findDeliveryChannels(
                    distinct.subList(from, Math.min(distinct.size(), from + CHUNK)))) {
                DeliveryChannel channel = (DeliveryChannel) row[1];
                channels.put((UUID) row[0], app.nadel.members.DeliveryChannel.valueOf(channel.name()));
            }
        }
        return channels;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> idsMatchingName(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return List.of();
        }
        String pattern = "%" + stripped.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
        return members.findIdsByName(pattern, PageRequest.of(0, MAX_NAME_MATCHES));
    }
}
