package app.nadel.members.internal;

import app.nadel.identity.Permission;
import java.util.EnumSet;
import java.util.Set;
import nadel.contract.model.BaseRole;
import nadel.contract.model.RoleDetails;

/** Turns a role into API output and reads its stored permission names. */
final class RoleDtoMapper {

    private RoleDtoMapper() {
    }

    static RoleDetails toDto(Role role, Set<Permission> permissions, long memberCount) {
        var dto = new RoleDetails(
                role.getId(),
                role.getName(),
                role.isNameCustomized(),
                role.isDescriptionCustomized(),
                permissions.stream().map(p -> nadel.contract.model.Permission.fromValue(p.name())).toList(),
                (int) memberCount);
        dto.setDescription(role.getDescription());
        if (role.getBaseCode() != null) {
            dto.setBaseCode(BaseRole.fromValue(role.getBaseCode()));
        }
        return dto;
    }

    /** Stored names that are not in the {@link Permission} enum grant nothing (fail closed). */
    static EnumSet<Permission> parse(Iterable<String> names) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        for (String name : names) {
            try {
                result.add(Permission.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // unknown name: grants nothing
            }
        }
        return result;
    }

    /** The effective permissions of a role: the Chairman role always has every permission. */
    static EnumSet<Permission> effective(Role role, Iterable<String> stored) {
        return PermissionUnion.CHAIRMAN.equals(role.getBaseCode()) ? EnumSet.allOf(Permission.class) : parse(stored);
    }
}
