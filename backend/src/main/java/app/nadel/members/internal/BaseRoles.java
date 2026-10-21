package app.nadel.members.internal;

import app.nadel.identity.Permission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The base roles of every cooperative and their initial permissions, as the application knows them. The SQL function
 * {@code seed_base_roles} (V2, last re-created by V10) writes the same matrix; {@code BaseRolesMatrixTests} fails when
 * the two drift apart.
 * Chairman always has every permission: {@link PermissionUnion} grants them in code whatever rows are stored.
 */
final class BaseRoles {

    record Definition(String code, String name, String description, Set<Permission> permissions) {
    }

    static final String CHAIRMAN = PermissionUnion.CHAIRMAN;
    static final String TREASURER = "TREASURER";
    static final String MEMBER = "MEMBER";

    /** In display order: base roles come first in the roles list, in this order. */
    static final List<Definition> ALL = List.of(
            new Definition(CHAIRMAN, "Chairman", "Full access", EnumSet.allOf(Permission.class)),
            new Definition(TREASURER, "Treasurer", "Read access to members, plots and audit",
                    EnumSet.of(Permission.MEMBERS_VIEW, Permission.PLOTS_VIEW, Permission.AUDIT_VIEW,
                            Permission.TARIFFS_VIEW)),
            new Definition(MEMBER, "Member", "Own data only", EnumSet.noneOf(Permission.class)));

    private BaseRoles() {
    }

    /**
     * The seed (stored) name of a base role, English, as {@code seed_base_roles} writes it. Restoring the standard
     * name of a renamed base role stores this value.
     *
     * @throws IllegalArgumentException for a code that is not a base role
     */
    static String defaultName(String code) {
        return ALL.stream().filter(d -> d.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a base role: " + code)).name();
    }

    /**
     * The seed (stored) description of a base role, English, as {@code seed_base_roles} writes it. Restoring the
     * standard description of a changed base role stores this value.
     *
     * @throws IllegalArgumentException for a code that is not a base role
     */
    static String defaultDescription(String code) {
        return ALL.stream().filter(d -> d.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a base role: " + code)).description();
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
