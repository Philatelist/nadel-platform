package app.nadel.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who the caller is inside the platform: the member behind the token, their roles and the union of the permissions
 * of those roles. Resolved from the database on every request, never cached across requests.
 *
 * @param tenantId   cooperative of the member; null for the platform operator
 * @param memberId   the member the token is linked to; null for the platform operator
 * @param subject    Keycloak user id ({@code sub})
 * @param displayName name to show, never logged
 */
public record AccessProfile(
        @Nullable UUID tenantId,
        @Nullable UUID memberId,
        String subject,
        String displayName,
        Set<Permission> permissions,
        List<RoleRef> roles,
        boolean platformOperator) {

    public AccessProfile {
        permissions = permissions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions)); // keeps the enum order
        roles = List.copyOf(roles);
    }

    /** The platform operator: no cooperative, no member, no permissions (ADR 0008). */
    public static AccessProfile operator(String subject, String displayName) {
        return new AccessProfile(null, null, subject, displayName, Set.of(), List.of(), true);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
