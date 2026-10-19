package app.nadel.members.internal;

import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import java.util.Collection;
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

    private final MemberRepository members;

    JpaMemberDirectory(MemberRepository members) {
        this.members = members;
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
    public Map<UUID, MemberRef> lockActive(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return members.lockAllById(ids).stream()
                .map(member -> new MemberRef(member.getId(), member.getLastName(), member.getFirstName(),
                        member.getMiddleName(), member.isArchived()))
                .collect(Collectors.toMap(MemberRef::id, Function.identity()));
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
