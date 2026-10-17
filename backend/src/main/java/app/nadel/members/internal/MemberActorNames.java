package app.nadel.members.internal;

import app.nadel.audit.ActorNames;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the acting members of audit entries to their current names (see {@link ActorNames}). */
@Component
class MemberActorNames implements ActorNames {

    private final MemberRepository members;

    MemberActorNames(MemberRepository members) {
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> memberIds) {
        return members.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::displayName));
    }
}
