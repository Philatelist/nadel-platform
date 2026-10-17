package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.RoleDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RoleQueries {

    private final RoleRepository roles;

    RoleQueries(RoleRepository roles) {
        this.roles = roles;
    }

    /** Every role of the caller's cooperative: base roles first (Chairman, Treasurer, Member), then custom by name. */
    @Transactional(readOnly = true)
    List<RoleDetails> list(AccessProfile viewer) {
        UUID tenant = viewer.tenantId();
        Map<UUID, List<String>> stored = new HashMap<>();
        for (RolePermissionRow row : roles.findAllPermissions(tenant)) {
            stored.computeIfAbsent(row.roleId(), id -> new ArrayList<>()).add(row.permission());
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (RoleMemberCount row : roles.countActiveHolders(tenant)) {
            counts.put(row.roleId(), row.count());
        }
        return roles.findAllOf(tenant).stream()
                .sorted(Comparator.comparingInt((Role r) -> BaseRoles.order(r.getBaseCode()))
                        .thenComparing(Role::getName, String.CASE_INSENSITIVE_ORDER))
                .map(role -> {
                    EnumSet<Permission> permissions = RoleDtoMapper.effective(role,
                            stored.getOrDefault(role.getId(), List.of()));
                    return RoleDtoMapper.toDto(role, permissions, counts.getOrDefault(role.getId(), 0L));
                })
                .toList();
    }
}
