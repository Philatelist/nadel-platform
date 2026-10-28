package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.TestPostgis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V26 drops the plaintext import plan and its digest. It must refuse to run while a plan has no sealed copy (the Java
 * migration 25.1 did not run) and, once it has, move the rule "only a preview keeps its plan" to {@code plan_enc}.
 * Runs on its own container without the Spring context: Flyway stops at V25 and the V26 script is executed by hand, so
 * the Java migration 25.1 (which would seal the plans) never runs between them.
 */
class ImportPlanMigrationGuardTests {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void v26RefusesToDropAPlaintextPlanWithoutCiphertextAndSucceedsOnceSealed() throws IOException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("25").load().migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            sql.update("insert into tenant (id, name) values (?, 'Guard Cooperative')", TENANT);
            UUID sealed = insert(sql, "PREVIEW");
            UUID plain = insert(sql, "PREVIEW");
            insert(sql, "HAS_ERRORS");
            sql.update("update import_run set plan = '{\"email\": \"guard26@example.com\"}', plan_digest = 'd' "
                    + "where id in (?, ?)", sealed, plain);
            // the migration 25.1 did not run for one of them: its plaintext plan has no ciphertext
            sql.update("update import_run set plan_enc = '\\x01'::bytea where id = ?", sealed);

            assertThatThrownBy(() -> sql.execute(script()))
                    .rootCause().hasMessageContaining("V26 aborted: 1 import runs")
                    .hasMessageNotContaining("guard26@example.com");
            assertThat(sql.queryForObject("select count(*) from information_schema.columns "
                    + "where table_name = 'import_run' and column_name in ('plan', 'plan_digest')", Integer.class))
                    .isEqualTo(2);

            // the migration 25.1 sealed it and cleared the plaintext
            sql.update("update import_run set plan_enc = '\\x02'::bytea, plan = null, plan_digest = null "
                    + "where id = ?", plain);
            sql.execute(script());

            assertThat(sql.queryForObject("select count(*) from information_schema.columns "
                    + "where table_name = 'import_run' and column_name in ('plan', 'plan_digest')", Integer.class))
                    .isZero();
            // only a preview keeps its (sealed) plan
            assertThatThrownBy(() -> sql.update("update import_run set status = 'STALE' where id = ?", plain))
                    .rootCause().hasMessageContaining("import_run_plan_ck");
            sql.update("update import_run set status = 'STALE', plan_enc = null where id = ?", plain);
            assertThatThrownBy(() -> sql.update("update import_run set plan_enc = '\\x03'::bytea "
                    + "where status = 'HAS_ERRORS'")).rootCause().hasMessageContaining("import_run_plan_ck");
        }
    }

    private static UUID insert(JdbcTemplate sql, String status) {
        UUID id = UUID.randomUUID();
        sql.update("insert into import_run (id, tenant_id, file_name, created_by_subject, created_by_name, "
                + "expires_at, status) values (?, ?, 'f.xlsx', 's', 'n', now() + interval '30 minutes', ?)", id,
                TENANT, status);
        return id;
    }

    private static String script() throws IOException {
        try (InputStream in = ImportPlanMigrationGuardTests.class
                .getResourceAsStream("/db/migration/V26__drop_import_plan_plaintext.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
