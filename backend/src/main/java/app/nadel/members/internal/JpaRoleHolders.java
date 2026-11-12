package app.nadel.members.internal;

import app.nadel.identity.Permission;
import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link RoleHolders} from the role assignments; RLS limits them to the caller's cooperative. */
@Component
class JpaRoleHolders implements RoleHolders {

    private static final Comparator<MemberRef> NAME_ORDER = Comparator.comparing(MemberRef::lastName)
            .thenComparing(MemberRef::firstName)
            .thenComparing(ref -> Objects.requireNonNullElse(ref.middleName(), ""))
            .thenComparing(ref -> ref.id().toString());

    private final MemberRepository members;

    JpaRoleHolders(MemberRepository members) {
        this.members = members;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MemberRef> activeHolders(String baseRoleCode) {
        Objects.requireNonNull(baseRoleCode, "baseRoleCode");
        return members.findActiveHoldersOf(baseRoleCode).stream()
                .map(member -> new MemberRef(member.getId(), member.getLastName(), member.getFirstName(),
                        member.getMiddleName(), member.isArchived()))
                .sorted(NAME_ORDER)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> activeWithPermission(Permission permission) {
        Objects.requireNonNull(permission, "permission");
        return members.findActiveIdsWithPermission(permission.name(), PermissionUnion.CHAIRMAN).stream()
                .sorted()
                .toList();
    }
}
