package app.nadel.members.internal;

import app.nadel.identity.RoleRef;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A role a member holds with its stored permission names. */
record RoleGrant(UUID id, String name, @Nullable String baseCode, boolean nameCustomized, Set<String> permissions) {

    boolean isChairman() {
        return PermissionUnion.CHAIRMAN.equals(baseCode);
    }

    RoleRef ref() {
        return new RoleRef(id, name, baseCode, nameCustomized);
    }
}
