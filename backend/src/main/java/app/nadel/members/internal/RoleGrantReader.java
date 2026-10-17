package app.nadel.members.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
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
        heads.forEach((id, head) -> grants.add(new RoleGrant(id, head.roleName(), head.baseCode(), permissions.get(id))));
        grants.sort(Comparator.comparing(RoleGrant::name, String.CASE_INSENSITIVE_ORDER));
        return grants;
    }
}
