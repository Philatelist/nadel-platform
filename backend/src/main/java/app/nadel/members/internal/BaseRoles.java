package app.nadel.members.internal;

import app.nadel.identity.Permission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The base roles of every cooperative and their initial permissions, as the application knows them. The SQL function
 * {@code seed_base_roles} (V2) writes the same matrix; {@code BaseRolesMatrixTests} fails when the two drift apart.
 * Chairman always has every permission: {@link PermissionUnion} grants them in code whatever rows are stored.
 */
final class BaseRoles {

    record Definition(String code, Set<Permission> permissions) {
    }

    static final String CHAIRMAN = PermissionUnion.CHAIRMAN;
    static final String TREASURER = "TREASURER";
    static final String MEMBER = "MEMBER";

    /** In display order: base roles come first in the roles list, in this order. */
    static final List<Definition> ALL = List.of(
            new Definition(CHAIRMAN, EnumSet.allOf(Permission.class)),
            new Definition(TREASURER,
                    EnumSet.of(Permission.MEMBERS_VIEW, Permission.PLOTS_VIEW, Permission.AUDIT_VIEW)),
            new Definition(MEMBER, EnumSet.noneOf(Permission.class)));

    private BaseRoles() {
    }

    /** Position of a base role in the display order; custom roles (null code) sort after all base roles. */
    static int order(String baseCode) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).code().equals(baseCode)) {
                return i;
            }
        }
        return ALL.size();
    }
}
