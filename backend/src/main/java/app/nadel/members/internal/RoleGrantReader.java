package app.nadel.members.internal;

import app.nadel.identity.RoleRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads the roles of a member with their permission names in one query. Call inside a transaction. */
@Component
class RoleGrantReader {

    private final MemberRepository members;

    RoleGrantReader(MemberRepository members) {
        this.members = members;
    }

    /** The member's roles ordered by name. */
    List<RoleGrant> read(Member member) {
        Map<UUID, TreeSet<String>> permissions = new LinkedHashMap<>();
        Map<UUID, RoleGrantRow> heads = new LinkedHashMap<>();
        for (RoleGrantRow row : members.findRoleGrants(member.getTenantId(), member.getId())) {
            heads.putIfAbsent(row.roleId(), row);
            TreeSet<String> set = permissions.computeIfAbsent(row.roleId(), id -> new TreeSet<>());
            if (row.permission() != null) {
                set.add(row.permission());
            }
        }
        List<RoleGrant> grants = new ArrayList<>();
        heads.forEach((id, head) -> grants.add(new RoleGrant(id, head.roleName(), head.baseCode(),
                head.nameCustomized(), permissions.get(id))));
        grants.sort(Comparator.comparing(RoleGrant::name, String.CASE_INSENSITIVE_ORDER));
        return grants;
    }

    /**
     * The roles of several members of one cooperative in a single query, each list ordered by role name. Members
     * without roles are absent from the map.
     */
    Map<UUID, List<RoleRef>> readRefs(UUID tenantId, Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return members.findRolesOfMembers(tenantId, memberIds).stream()
                .sorted(Comparator.comparing(MemberRoleRow::roleName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.groupingBy(MemberRoleRow::memberId, LinkedHashMap::new,
                        Collectors.mapping(r -> new RoleRef(r.roleId(), r.roleName(), r.baseCode(),
                                r.nameCustomized()),
                                Collectors.toList())));
    }
}
