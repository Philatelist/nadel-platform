package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.identity.Permission;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import nadel.contract.model.PermissionInfo;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

/** Unit tests of small pure parts of the role services. */
class RoleLogicTests {

    @Test
    void listsAllEightPermissionsInEnumOrderWithEnglishText() {
        List<PermissionInfo> all = PermissionCatalog.all();

        assertThat(all).extracting(p -> p.getCode().getValue())
                .containsExactly(Arrays.stream(Permission.values()).map(Enum::name).toArray(String[]::new));
        assertThat(all).allSatisfy(p -> {
            assertThat(p.getName()).isNotBlank();
            assertThat(p.getDescription()).isNotBlank();
        });
        assertThat(all.get(0).getName()).isEqualTo("View members");
        assertThat(all.get(2).getName()).isEqualTo("Manage roles");
    }

    @Test
    void roleSnapshotDiffListsOnlyChangedFields() {
        RoleSnapshot before = RoleSnapshot.of("Secretary", null, List.of(Permission.MEMBERS_VIEW));
        RoleSnapshot after = RoleSnapshot.of("Secretary", "Board", List.of(Permission.MEMBERS_VIEW,
                Permission.AUDIT_VIEW));

        assertThat(RoleSnapshot.diff(before, before)).isEmpty();
        assertThat(RoleSnapshot.diff(before, after)).extracting(c -> c.field())
                .containsExactly("description", "permissions");
        assertThat(RoleSnapshot.diff(before, after).get(1).newValue()).isEqualTo("AUDIT_VIEW, MEMBERS_VIEW");
    }

    @Test
    void auditNameUsesBaseTokenOnlyForNonCustomizedBaseRoles() {
        assertThat(RoleSnapshot.auditName("Treasurer", "TREASURER", false)).isEqualTo("base:TREASURER");
        assertThat(RoleSnapshot.auditName("Member", "MEMBER", false)).isEqualTo("base:MEMBER");
        assertThat(RoleSnapshot.auditName("Бухгалтер", "TREASURER", true)).isEqualTo("Бухгалтер");
        assertThat(RoleSnapshot.auditName("Secretary", null, true)).isEqualTo("Secretary");
        assertThat(RoleSnapshot.auditName("Secretary", null, false)).isEqualTo("Secretary");
    }

    @Test
    void anIndexViolationIsRecognisedThroughItsCauseChain() {
        var violation = new ConstraintViolationException("duplicate", new SQLException("x"), "role_tenant_name_uq");
        var other = new ConstraintViolationException("duplicate", new SQLException("x"), "member_tenant_email_uq");

        assertThat(RoleCommands.violatesNameIndex(new RuntimeException("wrapped", violation))).isTrue();
        assertThat(RoleCommands.violatesNameIndex(new RuntimeException("wrapped", other))).isFalse();
    }
}
