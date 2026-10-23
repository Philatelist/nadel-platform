package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.TestPostgis;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V12 on a database that already has cooperatives: the four meter permissions are added by targeted inserts only (never
 * by re-running {@code seed_base_roles}, which would restore a permission a chairman removed), and a cooperative created
 * afterwards gets the full matrix from the re-created function. Runs the migrations itself on a separate container: stop
 * at V11, create the cooperatives as the previous release did, then migrate to V12 (the ledger permissions of V13 are
 * covered by {@code LedgerMigrationTests}).
 */
class MetersMigrationTests {

    private static final List<String> CHAIRMAN_BEFORE = List.of("MEMBERS_VIEW", "MEMBERS_EDIT", "ROLES_MANAGE",
            "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN", "SETTINGS_MANAGE", "TARIFFS_VIEW",
            "TARIFFS_MANAGE");
    private static final List<String> METER_PERMISSIONS = List.of("METERS_VIEW", "METERS_MANAGE", "READINGS_ENTER",
            "READINGS_APPROVE");

    @Test
    void existingCooperativesGetTheMeterPermissionsByTargetedInserts() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            flyway(postgres, "11").migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            for (UUID tenant : List.of(first, second)) {
                sql.update("insert into tenant (id, name) values (?, 'Existing Cooperative')", tenant);
                sql.queryForList("select seed_base_roles(?)", tenant);
            }
            // the first chairman removed a permission from the Treasurer role; the second one renamed the role
            sql.update("delete from role_permission where role_id = md5(?::text || ':TREASURER')::uuid "
                    + "and permission = 'AUDIT_VIEW'", first);
            sql.update("update role set name = 'Cashier', name_customized = true where id = md5(?::text || "
                    + "':TREASURER')::uuid", second);
            assertThat(permissions(sql, first, "CHAIRMAN")).containsExactlyInAnyOrderElementsOf(CHAIRMAN_BEFORE);
            assertThat(sql.queryForObject("select count(*) from information_schema.tables where table_name = 'meter'",
                    Integer.class)).isZero();

            flyway(postgres, "12").migrate();

            for (UUID tenant : List.of(first, second)) {
                assertThat(permissions(sql, tenant, "CHAIRMAN")).hasSize(15).containsAll(CHAIRMAN_BEFORE)
                        .containsAll(METER_PERMISSIONS);
                assertThat(permissions(sql, tenant, "MEMBER")).isEmpty();
            }
            // Treasurer: only METERS_VIEW is added; the permission the first chairman removed stays removed
            assertThat(permissions(sql, first, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "TARIFFS_VIEW", "METERS_VIEW");
            assertThat(permissions(sql, second, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW");
            assertThat(sql.queryForObject("select name from role where id = md5(?::text || ':TREASURER')::uuid",
                    String.class, second)).as("a renamed role keeps its name").isEqualTo("Cashier");
            assertThat(sql.queryForObject("select count(*) from role_permission where permission in ('READINGS_ENTER', "
                    + "'READINGS_APPROVE', 'METERS_MANAGE') and role_id = md5(?::text || ':TREASURER')::uuid",
                    Integer.class, second)).isZero();

            // a cooperative created after V12 gets the whole matrix, the charge types and unchanged descriptions
            UUID fresh = UUID.randomUUID();
            sql.update("insert into tenant (id, name) values (?, 'New Cooperative')", fresh);
            sql.queryForList("select seed_base_roles(?)", fresh);
            assertThat(permissions(sql, fresh, "CHAIRMAN")).hasSize(15);
            assertThat(permissions(sql, fresh, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW");
            assertThat(sql.queryForList("select description from role where tenant_id = ? order by base_code",
                    String.class, fresh)).containsExactly("Full access", "Own data only",
                    "Read access to members, plots and audit");
            assertThat(sql.queryForObject("select count(*) from charge_type where tenant_id = ?", Integer.class, fresh))
                    .isEqualTo(4);
        }
    }

    private static Flyway flyway(PostgreSQLContainer postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static List<String> permissions(JdbcTemplate sql, UUID tenant, String baseCode) {
        return sql.queryForList("select permission from role_permission where role_id = md5(?::text || ':' || ?)::uuid",
                String.class, tenant, baseCode);
    }
}
