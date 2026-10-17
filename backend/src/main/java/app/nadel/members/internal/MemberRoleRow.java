package app.nadel.members.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One role held by one member, without permissions. */
record MemberRoleRow(UUID memberId, UUID roleId, String roleName, @Nullable String baseCode,
        boolean nameCustomized) {

    MemberRoleRow {
        nameCustomized = Role.isNameCustomized(baseCode, nameCustomized);
    }
}
