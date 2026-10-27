package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** Members, roles and their links obey RLS and composite foreign keys (plain SQL, no entities). */
class MembersTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final List<String> TABLES = List.of("member", "role", "role_permission", "member_role");

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantSeesOnlyItsOwnRows() {
        for (String table : TABLES) {
            List<UUID> demoTenants = as(DEMO, () -> tx.execute(s ->
                    jdbc.queryForList("select distinct tenant_id from " + table, UUID.class)));
            List<UUID> otherTenants = as(OTHER, () -> tx.execute(s ->
                    jdbc.queryForList("select distinct tenant_id from " + table, UUID.class)));

            assertThat(demoTenants).as(table).containsExactly(DEMO);
            assertThat(otherTenants).as(table).containsExactly(OTHER);
        }
    }

    @Test
    void withoutTenantNothingIsVisible() {
        for (String table : TABLES) {
            Integer count = tx.execute(s -> jdbc.queryForObject("select count(*) from " + table, Integer.class));
            assertThat(count).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowsForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member (tenant_id, last_name, first_name) values (?, 'X', 'Y')", OTHER))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into role (tenant_id, name) values (?, 'Rogue')", OTHER))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into role_permission (tenant_id, role_id, permission) values (?, gen_random_uuid(), 'MEMBERS_VIEW')",
                OTHER)))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_role (tenant_id, member_id, role_id) values (?, gen_random_uuid(), gen_random_uuid())",
                OTHER)))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotUpdateOrDeleteRowsOfAnotherTenant() {
        Integer updated = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update member set middle_name = 'tampered' where tenant_id = ?", OTHER)));
        Integer deleted = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from member_role where tenant_id = ?", OTHER)));

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
    }

    @Test
    void compositeForeignKeysRefuseCrossTenantLinks() {
        // Demo member linked to a role of the other cooperative: visible to the demo tenant only if the role were,
        // so insert as the demo tenant using the other tenant's deterministic base role id.
        UUID otherMemberRole = baseRoleId(OTHER, "MEMBER");
        UUID demoMember = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                DEMO, demoMember, otherMemberRole)))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'MEMBERS_VIEW')",
                DEMO, otherMemberRole)))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void roleInUseCannotBeDeleted() {
        UUID memberRole = baseRoleId(DEMO, "MEMBER");

        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update("delete from role where id = ?", memberRole))))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID baseRoleId(UUID tenant, String code) {
        return as(tenant, () -> tx.execute(s -> jdbc.queryForObject(
                "select id from role where base_code = ?", UUID.class, code)));
    }

    private static <T> T as(UUID tenant, Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("test-user")
                .claim("tenant_id", tenant.toString())
                .claim("email", "test@demo.local")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
