package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemberQueries {

    private final MemberRepository members;
    private final RoleGrantReader grants;

    MemberQueries(MemberRepository members, RoleGrantReader grants) {
        this.members = members;
        this.grants = grants;
    }

    /** The card of a member of the current cooperative (RLS hides other cooperatives), shaped for the viewer. */
    @Transactional(readOnly = true)
    Optional<nadel.contract.model.Member> card(UUID id, AccessProfile viewer) {
        return members.findById(id).map(member -> MemberDtoMapper.toDto(member, grants.read(member), viewer));
    }
}
