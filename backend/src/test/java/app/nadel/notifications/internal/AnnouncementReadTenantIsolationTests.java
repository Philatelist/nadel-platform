package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * announcement_read (V40): cooperatives are isolated (RLS with USING and WITH CHECK, nothing visible without a
 * cooperative), the table has the policy and a tenant index, the application role may only read and insert (a mark is
 * never changed or deleted by the application), the composite keys refuse a mark that crosses cooperatives, and a
 * second mark of the same announcement and member is refused (the application inserts with ON CONFLICT DO NOTHING).
 */
class AnnouncementReadTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    static final String TABLE = "announcement_read";

    private static final String INSERT_MARK = "insert into announcement_read (tenant_id, announcement_id, member_id) "
            + "values (?, ?, ?)";

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private UUID demoAnnouncement;
    private UUID otherAnnouncement;

    @BeforeAll
    static void createOwnerRole() {
        ownerJdbc().execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        ownerJdbc().execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
    }

    @BeforeEach
    void createFixtures() {
        clean();
        demoAnnouncement = announcement(DEMO);
        otherAnnouncement = announcement(OTHER);
        ownerJdbc().update(INSERT_MARK, DEMO, demoAnnouncement, DEMO_MEMBER);
        ownerJdbc().update(INSERT_MARK, OTHER, otherAnnouncement, OTHER_MEMBER);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        // the marks go with their announcement (ON DELETE CASCADE)
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            sql.update("delete from announcement where text = 'Isolation fixture'");
        });
    }

    @Test
    void theTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        assertThat(ownerJdbc().queryForObject(
                "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, TABLE))
                .isTrue();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation' "
                        + "and qual like '%current_tenant_id%' and with_check like '%current_tenant_id%'",
                Integer.class, TABLE)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                Integer.class, TABLE)).isEqualTo(1);
    }

    @Test
    void tenantSeesOnlyItsOwnRows() {
        List<UUID> demo = as(DEMO, () -> tx.execute(s ->
                jdbc.queryForList("select announcement_id from announcement_read", UUID.class)));
        List<UUID> other = as(OTHER, () -> tx.execute(s ->
                jdbc.queryForList("select announcement_id from announcement_read", UUID.class)));

        assertThat(demo).containsExactly(demoAnnouncement);
        assertThat(other).containsExactly(otherAnnouncement);
    }

    @Test
    void withoutTenantNothingIsVisible() {
        Integer count = tx.execute(s -> jdbc.queryForObject("select count(*) from announcement_read", Integer.class));
        assertThat(count).isZero();
    }

    @Test
    void cannotInsertRowsForAnotherTenant() {
        // a mark that is valid for the other cooperative: only the policy refuses it
        UUID fresh = announcement(OTHER);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT_MARK, OTHER, fresh,
                OTHER_MEMBER)))).isInstanceOf(DataAccessException.class);
        assertThat(ownerJdbc().queryForObject("select count(*) from announcement_read where announcement_id = ?",
                Integer.class, fresh)).isZero();
    }

    @Test
    void compositeForeignKeysRefuseCrossTenantLinks() {
        // this cooperative's mark of another cooperative's announcement, or of another cooperative's member
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT_MARK, DEMO, otherAnnouncement,
                DEMO_MEMBER)))).isInstanceOf(DataAccessException.class);
        UUID second = announcement(DEMO);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT_MARK, DEMO, second,
                OTHER_MEMBER)))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aMarkIsWrittenOnceAndNeverChangedOrDeletedByTheApplication() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT_MARK, DEMO, demoAnnouncement,
                DEMO_MEMBER)))).isInstanceOf(DataAccessException.class);
        Integer inserted = as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT_MARK + " on conflict do nothing", DEMO,
                demoAnnouncement, DEMO_MEMBER)));
        assertThat(inserted).isZero();
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update announcement_read set read_at = now() where tenant_id = ?", DEMO))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from announcement_read where tenant_id = ?", DEMO))))
                .isInstanceOf(DataAccessException.class);
        for (String right : List.of("SELECT", "INSERT")) {
            assertThat(privilege(right)).as(right).isTrue();
        }
        for (String right : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(privilege(right)).as(right).isFalse();
        }
    }

    private static boolean privilege(String right) {
        return Boolean.TRUE.equals(ownerJdbc().queryForObject("select has_table_privilege('nadel_app', ?, ?)",
                Boolean.class, "public." + TABLE, right));
    }

    private static UUID announcement(UUID tenant) {
        return ownerJdbc().queryForObject("insert into announcement (tenant_id, text, channels) "
                + "values (?, 'Isolation fixture', '{EMAIL}') returning id", UUID.class, tenant);
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
