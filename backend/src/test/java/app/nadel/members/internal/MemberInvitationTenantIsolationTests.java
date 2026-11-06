package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * member_access and member_invitation (V36): cooperatives are isolated (RLS with USING and WITH CHECK, nothing visible
 * without a cooperative), both tables have the policy and a tenant index, links never cross cooperatives, and the
 * application role cannot delete or rewrite an invitation.
 */
class MemberInvitationTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    static final List<String> TABLES = List.of("member_access", "member_invitation");

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void createFixtures() {
        clean();
        for (UUID[] pair : new UUID[][] {{DEMO, DEMO_MEMBER}, {OTHER, OTHER_MEMBER}}) {
            ownerJdbc().update("insert into member_access (member_id, tenant_id, state, invited_at) "
                    + "values (?, ?, 'INVITED', now())", pair[1], pair[0]);
            ownerJdbc().update("insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, "
                    + "expires_at, sent_at) values (?, ?, sha256(?::bytea), sha256('e'::bytea), "
                    + "now() + interval '1 day', now())", pair[0], pair[1], pair[1].toString().getBytes());
        }
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        ownerJdbc().update("delete from member_invitation where member_id in (?, ?)", DEMO_MEMBER, OTHER_MEMBER);
        ownerJdbc().update("delete from member_access where member_id in (?, ?)", DEMO_MEMBER, OTHER_MEMBER);
    }

    @Test
    void everyTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation' "
                            + "and qual like '%current_tenant_id%' and with_check like '%current_tenant_id%'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
        }
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
                "insert into member_access (member_id, tenant_id, state) values (gen_random_uuid(), ?, 'NONE')",
                OTHER)))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, expires_at) "
                        + "values (?, ?, sha256('x'::bytea), sha256('y'::bytea), now())", OTHER, OTHER_MEMBER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotUpdateRowsOfAnotherTenant() {
        Integer access = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update member_access set state = 'DISABLED' where tenant_id = ?", OTHER)));
        Integer invitation = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update member_invitation set revoked_at = now() where tenant_id = ?", OTHER)));

        assertThat(access).isZero();
        assertThat(invitation).isZero();
    }

    @Test
    void compositeForeignKeysRefuseCrossTenantLinks() {
        // a demo-cooperative row pointing at a member of the other cooperative
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_access (member_id, tenant_id, state) values (?, ?, 'NONE')",
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"), DEMO))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, expires_at) "
                        + "values (?, ?, sha256('z'::bytea), sha256('y'::bytea), now())", DEMO, OTHER_MEMBER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theApplicationRoleCannotDeleteAnInvitationOrRewriteItsToken() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from member_invitation where member_id = ?", DEMO_MEMBER))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update member_invitation set token_hash = sha256('n'::bytea) where member_id = ?", DEMO_MEMBER))))
                .isInstanceOf(DataAccessException.class);
        Integer used = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update member_invitation set used_at = now() where member_id = ?", DEMO_MEMBER)));
        assertThat(used).isEqualTo(1);
    }

    @Test
    void onlyOneOpenLinkPerMember() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, expires_at, sent_at) "
                        + "values (?, ?, sha256('second'::bytea), sha256('e'::bytea), now(), now())",
                DEMO, DEMO_MEMBER)))).isInstanceOf(DataAccessException.class);
        // a pending row (not sent yet) is allowed next to the open link
        Integer pending = as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, expires_at) "
                        + "values (?, ?, sha256('pending'::bytea), sha256('e'::bytea), now())",
                DEMO, DEMO_MEMBER)));
        assertThat(pending).isEqualTo(1);
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
