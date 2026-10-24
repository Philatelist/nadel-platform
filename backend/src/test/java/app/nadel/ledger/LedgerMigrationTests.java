package app.nadel.ledger;

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
 * V13 on a database that already has cooperatives: the four ledger permissions are added by targeted inserts only
 * (never by re-running {@code seed_base_roles}, which would restore a permission a chairman removed), every cooperative
 * gets the fifth charge type {@code COMMON_CONSUMERS} without losing a renamed or archived one, and a cooperative created
 * afterwards gets the full matrix and five types from the re-created functions. Runs the migrations itself on a
 * separate container: stop at V12, create the cooperatives as the previous release did, then migrate to the latest
 * version.
 */
class LedgerMigrationTests {

    private static final List<String> LEDGER_PERMISSIONS = List.of("CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE",
            "PERIODS_CLOSE");

    @Test
    void existingCooperativesGetTheLedgerPermissionsAndTheFifthChargeTypeByTargetedInserts() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            flyway(postgres, "12").migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID customOne = UUID.randomUUID();
            UUID customTwo = UUID.randomUUID();
            for (UUID tenant : List.of(first, second)) {
                sql.update("insert into tenant (id, name) values (?, 'Existing Cooperative')", tenant);
                sql.queryForList("select seed_base_roles(?)", tenant);
            }
            // the first chairman removed a permission from the Treasurer role and renamed the water type
            sql.update("delete from role_permission where role_id = md5(?::text || ':TREASURER')::uuid "
                    + "and permission = 'METERS_VIEW'", first);
            sql.update("update charge_type set name = 'Водопостачання', name_customized = true "
                    + "where tenant_id = ? and code = 'WATER'", first);
            // the second chairman created active custom types named like the defaults of the type V13 seeds
            sql.update("insert into charge_type (id, tenant_id, name, position) values (?, ?, 'common consumers', 5)",
                    customOne, second);
            sql.update("insert into charge_type (id, tenant_id, name, position) values (?, ?, 'Спільні споживачі', 6)",
                    customTwo, second);
            assertThat(permissions(sql, first, "CHAIRMAN")).hasSize(15);
            assertThat(sql.queryForObject("select count(*) from charge_type where tenant_id = ?", Integer.class, first))
                    .isEqualTo(4);

            flyway(postgres, null).migrate();

            for (UUID tenant : List.of(first, second)) {
                // V13 adds the four ledger permissions, V19 RECALCULATION_RUN
                assertThat(permissions(sql, tenant, "CHAIRMAN")).hasSize(20).containsAll(LEDGER_PERMISSIONS)
                        .contains("RECALCULATION_RUN");
                assertThat(permissions(sql, tenant, "MEMBER")).isEmpty();
                assertThat(sql.queryForList("select code from charge_type where tenant_id = ? and code is not null order by position",
                        String.class, tenant)).containsExactly("MEMBERSHIP", "ELECTRICITY", "LAND_TAX", "WATER",
                        "COMMON_CONSUMERS");
                assertThat(sql.queryForObject("select name || '|' || active || '|' || name_customized || '|' "
                        + "|| (id = md5(?::text || ':CHARGE_TYPE:COMMON_CONSUMERS')::uuid) from charge_type "
                        + "where tenant_id = ? and code = 'COMMON_CONSUMERS'", String.class, tenant, tenant))
                        .isEqualTo("Common consumers|true|false|true");
            }
            // custom types that clashed with the default names were renamed, still exist, and are not archived
            assertThat(sql.queryForList("select name from charge_type where tenant_id = ? and id in (?, ?) "
                    + "and archived_at is null and code is null and name_customized order by position",
                    String.class, second, customOne, customTwo)).containsExactly(
                    "common consumers (" + customOne.toString().substring(0, 8) + ")",
                    "Спільні споживачі (" + customTwo.toString().substring(0, 8) + ")");
            // the removed permission stays removed; the Treasurer gets CHARGES_VIEW only
            assertThat(permissions(sql, first, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW", "CHARGES_VIEW");
            assertThat(permissions(sql, second, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW");
            assertThat(sql.queryForObject("select name from charge_type where tenant_id = ? and code = 'WATER'",
                    String.class, first)).as("a renamed type keeps its name").isEqualTo("Водопостачання");

            // a cooperative created after V13 gets the whole matrix and the five charge types
            UUID fresh = UUID.randomUUID();
            sql.update("insert into tenant (id, name) values (?, 'New Cooperative')", fresh);
            sql.queryForList("select seed_base_roles(?)", fresh);
            assertThat(permissions(sql, fresh, "CHAIRMAN")).hasSize(20);
            assertThat(permissions(sql, fresh, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW");
            assertThat(sql.queryForObject("select count(*) from charge_type where tenant_id = ?", Integer.class, fresh))
                    .isEqualTo(5);
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
