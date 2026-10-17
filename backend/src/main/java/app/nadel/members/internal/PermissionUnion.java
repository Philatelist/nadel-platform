package app.nadel.members.internal;

import app.nadel.identity.Permission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Union of the permissions of all roles of a member. The Chairman base role always yields every permission in code,
 * whatever rows are stored, so a permission added later needs no migration for the chairman. Stored names that are
 * not in the {@link Permission} enum are ignored (fail closed).
 */
final class PermissionUnion {

    static final String CHAIRMAN = "CHAIRMAN";

    private PermissionUnion() {
    }

    static Set<Permission> of(Collection<RoleGrant> roles) {
        EnumSet<Permission> union = EnumSet.noneOf(Permission.class);
        for (RoleGrant role : roles) {
            if (role.isChairman()) {
                return EnumSet.allOf(Permission.class);
            }
            for (String name : role.permissions()) {
                try {
                    union.add(Permission.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // unknown name: grants nothing
                }
            }
        }
        return union;
    }
}
