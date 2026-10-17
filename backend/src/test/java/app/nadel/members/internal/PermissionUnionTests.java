package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.identity.Permission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PermissionUnionTests {

    @Test
    void noRolesGiveNoPermissions() {
        assertThat(PermissionUnion.of(List.of())).isEmpty();
    }

    @Test
    void roleWithoutPermissionsGivesNothing() {
        assertThat(PermissionUnion.of(List.of(role("MEMBER", Set.of())))).isEmpty();
    }

    @Test
    void permissionsOfSeveralRolesAreUnited() {
        var treasurer = role("TREASURER", Set.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW"));
        var secretary = role(null, Set.of("MEMBERS_VIEW", "MEMBERS_EDIT"));

        assertThat(PermissionUnion.of(List.of(treasurer, secretary))).containsExactlyInAnyOrder(
                Permission.MEMBERS_VIEW, Permission.PLOTS_VIEW, Permission.AUDIT_VIEW, Permission.MEMBERS_EDIT);
    }

    @Test
    void chairmanHasEveryPermissionWhateverIsStored() {
        assertThat(PermissionUnion.of(List.of(role("CHAIRMAN", Set.of())))).containsExactlyInAnyOrder(
                Permission.values());
    }

    @Test
    void chairmanTogetherWithOtherRolesStillHasEverything() {
        var chairman = role("CHAIRMAN", Set.of("MEMBERS_VIEW"));
        var member = role("MEMBER", Set.of());

        assertThat(PermissionUnion.of(List.of(member, chairman))).containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    void unknownStoredPermissionGrantsNothing() {
        var custom = role(null, Set.of("MEMBERS_VIEW", "LAUNCH_ROCKETS"));

        assertThat(PermissionUnion.of(List.of(custom))).containsExactly(Permission.MEMBERS_VIEW);
    }

    private static RoleGrant role(String baseCode, Set<String> permissions) {
        return new RoleGrant(UUID.randomUUID(), "Role", baseCode, baseCode == null, permissions);
    }
}
