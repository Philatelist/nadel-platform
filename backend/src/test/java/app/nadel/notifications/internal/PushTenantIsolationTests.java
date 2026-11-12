package app.nadel.notifications.internal;

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
 * push_subscription and push_throttle (V39): cooperatives are isolated (RLS with USING and WITH CHECK, nothing visible
 * without a cooperative), both tables have the policy, a tenant index and no TRUNCATE for the application role, and the
 * constraints of a device row hold.
 */
class PushTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    static final List<String> TABLES = List.of("push_subscription", "push_throttle");

    private static final String INSERT_DEVICE = "insert into push_subscription (id, tenant_id, member_id, "
            + "endpoint_enc, p256dh_enc, auth_enc, endpoint_index, server_key_id, label, language) "
            + "values (gen_random_uuid(), ?, ?, '\\x01', '\\x02', '\\x03', sha256(?::bytea), "
            + "'\\x0102030405060708', ?, ?)";

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void createFixtures() {
        clean();
        for (UUID[] pair : new UUID[][] {{DEMO, DEMO_MEMBER}, {OTHER, OTHER_MEMBER}}) {
            ownerJdbc().update(INSERT_DEVICE, pair[0], pair[1], pair[1].toString().getBytes(), "phone", "uk");
            ownerJdbc().update("insert into push_throttle (tenant_id, member_id, topic, last_sent_at) "
                    + "values (?, ?, 'READINGS_TO_APPROVE', now())", pair[0], pair[1]);
        }
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        ownerJdbc().update("delete from push_subscription where member_id in (?, ?)", DEMO_MEMBER, OTHER_MEMBER);
        ownerJdbc().update("delete from push_throttle where member_id in (?, ?)", DEMO_MEMBER, OTHER_MEMBER);
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
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id%'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
        }
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from pg_indexes where tablename = 'push_subscription' "
                        + "and indexdef like '%(tenant_id)'", Integer.class)).isEqualTo(1);
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
                INSERT_DEVICE, OTHER, OTHER_MEMBER, "x".getBytes(), "phone", "uk"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into push_throttle (tenant_id, member_id, topic, last_sent_at) "
                        + "values (?, ?, 'READINGS_TO_APPROVE', now())", OTHER, OTHER_MEMBER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotChangeOrDeleteRowsOfAnotherTenant() {
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                "update push_subscription set consecutive_failures = 1 where tenant_id = ?", OTHER)))).isZero();
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from push_subscription where tenant_id = ?", OTHER)))).isZero();
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                "update push_throttle set last_sent_at = now() where tenant_id = ?", OTHER)))).isZero();
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from push_throttle where tenant_id = ?", OTHER)))).isZero();
    }

    @Test
    void compositeForeignKeysRefuseCrossTenantLinks() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, OTHER_MEMBER, "z".getBytes(), "phone", "uk"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into push_throttle (tenant_id, member_id, topic, last_sent_at) "
                        + "values (?, ?, 'READINGS_TO_APPROVE', now())", DEMO, OTHER_MEMBER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theApplicationRoleHasFullDmlButNoTruncate() {
        for (String table : TABLES) {
            for (String right : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(ownerJdbc().queryForObject("select has_table_privilege('nadel_app', ?, ?)",
                        Boolean.class, "public." + table, right)).as("%s on %s", right, table).isTrue();
            }
            assertThat(ownerJdbc().queryForObject("select has_table_privilege('nadel_app', ?, 'TRUNCATE')",
                    Boolean.class, "public." + table)).as("TRUNCATE on %s", table).isFalse();
        }
    }

    @Test
    void oneEndpointIsStoredOncePerCooperative() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, DEMO_MEMBER, DEMO_MEMBER.toString().getBytes(), "again", "uk"))))
                .isInstanceOf(DataAccessException.class);
        // the same endpoint in another cooperative is another row
        assertThat(rows(OTHER, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, OTHER, OTHER_MEMBER, DEMO_MEMBER.toString().getBytes(), "phone", "en")))).isEqualTo(1);
    }

    @Test
    void checksRefuseBadValues() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, DEMO_MEMBER, "l".getBytes(), "x".repeat(81), "uk"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, DEMO_MEMBER, "g".getBytes(), "phone", "de"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update push_subscription set consecutive_failures = -1 where member_id = ?", DEMO_MEMBER))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update push_subscription set server_key_id = '\\x01' where member_id = ?", DEMO_MEMBER))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update push_throttle set topic = 'OTHER' where member_id = ?", DEMO_MEMBER))))
                .isInstanceOf(DataAccessException.class);
        // a label of exactly 80 characters and no label are fine
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, DEMO_MEMBER, "ok".getBytes(), "x".repeat(80), "en")))).isEqualTo(1);
        assertThat(rows(DEMO, () -> tx.execute(s -> jdbc.update(
                INSERT_DEVICE, DEMO, DEMO_MEMBER, "nl".getBytes(), null, "en")))).isEqualTo(1);
    }

    private int rows(UUID tenant, Supplier<Integer> action) {
        Integer n = as(tenant, action);
        return n == null ? -1 : n;
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
