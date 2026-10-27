package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.TestPostgis;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V20 tables {@code report_template} and {@code report_template_version} and the requisite columns of
 * {@code cooperative_settings}: cooperative isolation (RLS as nadel_app), privileges and the append-only guard, the
 * CHECKs and partial unique indexes, and the migration of a database that already has cooperatives. Runs the
 * migrations itself on a separate container; roles are switched with {@code SET LOCAL ROLE} inside one transaction.
 */
class ReportTemplateTablesTests {

    private static final String TEMPLATE_INSERT = "insert into report_template (id, tenant_id, slot, name) "
            + "values (?, ?, ?, ?)";
    private static final String VERSION_INSERT = "insert into report_template_version (tenant_id, template_id, "
            + "version_no, file_name, content, size_bytes, sha256) values (?, ?, ?, 'a.xlsx', ?, ?, ?)";
    private static final String SHA = "a".repeat(64);
    private static final byte[] BYTES = {1, 2, 3};

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate superuser;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TestPostgis.image()).withInitScript("db/test-init.sql");
        postgres.start();
        flyway(null).migrate();
        superuser = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        createOwnerRole(superuser);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    // ---- isolation

    @Test
    void tablesHaveRowLevelSecurityPolicyAndTenantIndex() {
        for (String table : List.of("report_template", "report_template_version")) {
            assertThat(superuser.queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(superuser.queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(superuser.queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id,%'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void aCooperativeSeesOnlyItsOwnTemplatesAndVersions() {
        UUID template = seedTemplate(tenantA, "Isolation A", null);
        seedTemplate(tenantB, "Isolation B", null);

        assertThat(asApp(tenantB, sql -> sql.queryForObject(
                "select count(*) from report_template where id = ?", Integer.class, template))).isZero();
        assertThat(asApp(tenantB, sql -> sql.queryForObject(
                "select count(*) from report_template_version where template_id = ?", Integer.class, template)))
                .isZero();
        assertThat(asApp(tenantA, sql -> sql.queryForObject(
                "select count(*) from report_template where id = ?", Integer.class, template))).isEqualTo(1);
        assertThat(asApp(tenantA, sql -> sql.queryForObject(
                "select count(*) from report_template_version where template_id = ?", Integer.class, template)))
                .isEqualTo(1);
        assertThat(asApp(tenantB, sql -> sql.queryForObject(
                "select count(*) from report_template where tenant_id <> current_tenant_id()", Integer.class)))
                .isZero();
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        seedTemplate(tenantA, "Nobody sees", null);

        assertThat(asApp(null, sql -> sql.queryForObject("select count(*) from report_template", Integer.class)))
                .isZero();
        assertThat(asApp(null, sql -> sql.queryForObject("select count(*) from report_template_version",
                Integer.class))).isZero();
    }

    @Test
    void anotherCooperativeCannotUpdateARow() {
        UUID template = seedTemplate(tenantA, "Not yours", null);

        Integer changed = asApp(tenantB, sql -> sql.update("update report_template set description = 'x' where id = ?",
                template));

        assertThat(changed).isZero();
        assertThat(superuser.queryForObject("select description from report_template where id = ?", String.class,
                template)).isNull();
    }

    @Test
    void anotherCooperativeCannotInsertRowsForThisOne() {
        UUID template = seedTemplate(tenantA, "Target", null);

        assertSqlState("42501", () -> asApp(tenantB, sql -> sql.update(TEMPLATE_INSERT, UUID.randomUUID(), tenantA, null,
                "Forged")));
        assertSqlState("42501", () -> asApp(tenantB, sql -> sql.update(VERSION_INSERT, tenantA, template, 9, BYTES, 3,
                SHA)));
    }

    @Test
    void aVersionCannotBeAddedToATemplateOfAnotherCooperative() {
        UUID template = seedTemplate(tenantA, "Composite", null);

        // tenant B's own tenant_id passes RLS, but (tenant_id, template_id) does not reference a template of B
        assertSqlState("23503", () -> asApp(tenantB, sql -> sql.update(VERSION_INSERT, tenantB, template, 5, BYTES, 3,
                SHA)));
    }

    // ---- privileges

    @Test
    void theApplicationCannotDeleteOrTruncateTemplatesNorChangeOrDeleteVersions() {
        UUID template = seedTemplate(tenantA, "Privileges", null);

        assertSqlState("42501", () -> asApp(tenantA, sql -> sql.update("delete from report_template where id = ?",
                template)));
        assertSqlState("42501", () -> execApp(tenantA, sql -> sql.execute("truncate report_template")));
        assertSqlState("42501", () -> asApp(tenantA, sql -> sql.update(
                "update report_template_version set file_name = 'b.xlsx' where template_id = ?", template)));
        assertSqlState("42501", () -> asApp(tenantA, sql -> sql.update(
                "delete from report_template_version where template_id = ?", template)));
        assertSqlState("42501", () -> execApp(tenantA, sql -> sql.execute("truncate report_template_version")));
        // UPDATE of the template stays (archive)
        assertThat(asApp(tenantA, sql -> sql.update(
                "update report_template set status = 'ARCHIVED', archived_at = now() where id = ?", template)))
                .isEqualTo(1);
    }

    @Test
    void theGuardTriggerStopsTheOwnerUnlessMaintenanceIsOn() {
        UUID template = seedTemplate(tenantA, "Guard", null);

        assertSqlState("42501", () -> asOwner(false, sql -> sql.update(
                "update report_template_version set file_name = 'b.xlsx' where template_id = ?", template)));
        assertSqlState("42501", () -> asOwner(false, sql -> sql.update(
                "delete from report_template_version where template_id = ?", template)));
        assertSqlState("42501", () -> execOwner(false, sql -> sql.execute("truncate report_template_version")));
        assertThat(asOwner(true, sql -> sql.update(
                "update report_template_version set file_name = 'b.xlsx' where template_id = ?", template)))
                .isEqualTo(1);
        assertThat(asOwner(true, sql -> sql.update(
                "delete from report_template_version where template_id = ?", template))).isEqualTo(1);
    }

    // ---- constraints

    @Test
    void anActiveNameIsUniqueIgnoringCaseAndReusableAfterArchive() {
        UUID first = seedTemplate(tenantA, "Statement", null);

        assertSqlState("23505", () -> seedTemplate(tenantA, "STATEMENT", null));
        assertThat(seedTemplate(tenantB, "Statement", null)).isNotNull();
        superuser.update("update report_template set status = 'ARCHIVED', archived_at = now() where id = ?", first);
        assertThat(seedTemplate(tenantA, "statement", null)).isNotNull();
    }

    @Test
    void onlyOneActiveTemplateHoldsTheTaxSlot() {
        UUID first = seedTemplate(tenantA, "Tax one", "TAX");

        assertSqlState("23505", () -> seedTemplate(tenantA, "Tax two", "TAX"));
        assertSqlState("23514", () -> seedTemplate(tenantA, "Bad slot", "OTHER"));
        superuser.update("update report_template set status = 'ARCHIVED', archived_at = now() where id = ?", first);
        assertThat(seedTemplate(tenantA, "Tax three", "TAX")).isNotNull();
    }

    @Test
    void archivedStatusRequiresArchivedAtAndNameRulesHold() {
        UUID template = seedTemplate(tenantA, "Archive rule", null);

        assertSqlState("23514", () -> superuser.update("update report_template set status = 'ARCHIVED' where id = ?",
                template));
        assertSqlState("23514", () -> superuser.update(
                "update report_template set archived_at = now() where id = ?", template));
        assertSqlState("23514", () -> seedTemplate(tenantA, "   ", null));
        assertSqlState("23514", () -> seedTemplate(tenantA, "x".repeat(201), null));
    }

    @Test
    void versionSizeHashAndNumberAreChecked() {
        UUID template = seedTemplate(tenantA, "Versions", null);
        byte[] big = new byte[2 * 1024 * 1024 + 1];

        assertSqlState("23514", () -> superuser.update(VERSION_INSERT, tenantA, template, 2, big, big.length, SHA));
        assertSqlState("23514", () -> superuser.update(VERSION_INSERT, tenantA, template, 2, BYTES, 4, SHA));
        assertSqlState("23514", () -> superuser.update(VERSION_INSERT, tenantA, template, 2, BYTES, 3, "G".repeat(64)));
        assertSqlState("23514", () -> superuser.update(VERSION_INSERT, tenantA, template, 2, BYTES, 3, "A".repeat(64)));
        assertSqlState("23514", () -> superuser.update(VERSION_INSERT, tenantA, template, 0, BYTES, 3, SHA));
        assertSqlState("23505", () -> superuser.update(VERSION_INSERT, tenantA, template, 1, BYTES, 3, SHA));
        assertThat(superuser.update(VERSION_INSERT, tenantA, template, 2, BYTES, 3, SHA)).isEqualTo(1);
        // exactly 2 MiB is allowed
        byte[] max = new byte[2 * 1024 * 1024];
        assertThat(superuser.update(VERSION_INSERT, tenantA, template, 3, max, max.length, SHA)).isEqualTo(1);
    }

    @Test
    void requisitesAreChecked() {
        superuser.update("insert into tenant (id, name) values (?, 'Requisites')", tenantA);
        superuser.update("insert into cooperative_settings (tenant_id) values (?)", tenantA);
        String update = "update cooperative_settings set %s = ? where tenant_id = ?";

        assertSqlState("23514", () -> superuser.update(update.formatted("iban"), "UA12", tenantA));
        assertSqlState("23514", () -> superuser.update(update.formatted("iban"), "PL" + "1".repeat(27), tenantA));
        assertSqlState("23514", () -> superuser.update(update.formatted("official_name"), "x".repeat(301), tenantA));
        assertSqlState("23514", () -> superuser.update(update.formatted("legal_address"), "x".repeat(501), tenantA));
        assertSqlState("23514", () -> superuser.update(update.formatted("bank_name"), "x".repeat(201), tenantA));
        assertThat(superuser.update(update.formatted("iban"), "UA" + "1".repeat(27), tenantA)).isEqualTo(1);
    }

    // ---- migration of an existing database

    @Test
    void existingCooperativesKeepTheirRowsAndGetNewColumnsAsNull() {
        try (PostgreSQLContainer old = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            old.start();
            Flyway.configure().dataSource(old.getJdbcUrl(), old.getUsername(), old.getPassword())
                    .locations("classpath:db/migration").target("19").load().migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(old.getJdbcUrl(), old.getUsername(),
                    old.getPassword()));
            UUID tenant = UUID.randomUUID();
            sql.update("insert into tenant (id, name) values (?, 'Existing')", tenant);
            sql.queryForList("select seed_base_roles(?)", tenant);
            sql.update("insert into cooperative_settings (tenant_id) values (?)", tenant);
            sql.update("delete from role_permission where role_id = md5(?::text || ':CHAIRMAN')::uuid "
                    + "and permission = 'METERS_VIEW'", tenant);
            assertThat(sql.queryForObject("select count(*) from information_schema.tables "
                    + "where table_name = 'report_template'", Integer.class)).isZero();

            Flyway.configure().dataSource(old.getJdbcUrl(), old.getUsername(), old.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            assertThat(sql.queryForObject("select count(*) from tenant where id = ?", Integer.class, tenant))
                    .isEqualTo(1);
            assertThat(sql.queryForMap("select official_name, legal_address, bank_name, iban from cooperative_settings "
                    + "where tenant_id = ?", tenant)).containsOnlyKeys("official_name", "legal_address", "bank_name",
                    "iban").containsValues(null, null, null, null);
            assertThat(sql.queryForObject("select count(*) from report_template", Integer.class)).isZero();
            assertThat(sql.queryForList("select permission from role_permission where role_id = "
                    + "md5(?::text || ':CHAIRMAN')::uuid", String.class, tenant))
                    .contains("REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE").doesNotContain("METERS_VIEW");
        }
    }

    // ---- helpers

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void createOwnerRole(JdbcTemplate sql) {
        sql.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        sql.execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
        sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
    }

    /** Creates the cooperative if needed, then a template with one version, as the superuser. */
    private UUID seedTemplate(UUID tenant, String name, String slot) {
        superuser.update("insert into tenant (id, name) values (?, 'Cooperative') on conflict do nothing", tenant);
        UUID id = UUID.randomUUID();
        superuser.update(TEMPLATE_INSERT, id, tenant, slot, name);
        if (superuser.queryForObject("select count(*) from report_template_version where template_id = ?",
                Integer.class, id) == 0) {
            superuser.update(VERSION_INSERT, tenant, id, 1, BYTES, 3, SHA);
        }
        return id;
    }

    private static Integer asApp(UUID tenant, Function<JdbcTemplate, Integer> work) {
        return inTransaction("nadel_app", tenant, false, work);
    }

    private static Integer asOwner(boolean maintenance, Function<JdbcTemplate, Integer> work) {
        return inTransaction("nadel_owner", null, maintenance, work);
    }

    private static void execApp(UUID tenant, Consumer<JdbcTemplate> work) {
        asApp(tenant, sql -> {
            work.accept(sql);
            return 0;
        });
    }

    private static void execOwner(boolean maintenance, Consumer<JdbcTemplate> work) {
        asOwner(maintenance, sql -> {
            work.accept(sql);
            return 0;
        });
    }

    private static <T> T inTransaction(String role, UUID tenant, boolean maintenance, Function<JdbcTemplate, T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(superuser.getDataSource())).execute(status -> {
            superuser.execute("SET LOCAL ROLE " + role);
            if (tenant != null) {
                superuser.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            }
            if (maintenance) {
                superuser.execute("SET LOCAL app.ledger_maintenance = 'on'");
            }
            return work.apply(superuser);
        });
    }

    private static void assertSqlState(String expected, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class).satisfies(error -> {
            Throwable cause = error.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(expected);
        });
    }
}
