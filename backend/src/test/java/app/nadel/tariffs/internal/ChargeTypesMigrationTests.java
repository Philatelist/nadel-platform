package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V10 on a database that already has cooperatives: the new permissions are added by targeted inserts only (never by
 * re-running {@code seed_base_roles}, which would restore a permission a chairman removed), and every existing
 * cooperative gets its four charge types. Runs the migrations itself on a separate container: stop at V9, create the
 * cooperatives as the previous release did, then migrate to the latest version.
 */
class ChargeTypesMigrationTests {

    @Test
    void existingCooperativesGetTheNewPermissionsByTargetedInsertsAndTheirChargeTypes() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            Flyway before = flyway(postgres, "9");
            before.migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            for (UUID tenant : List.of(first, second)) {
                sql.update("insert into tenant (id, name) values (?, 'Existing Cooperative')", tenant);
                sql.queryForList("select seed_base_roles(?)", tenant);
            }
            // the first chairman removed a permission from the Treasurer role; the second one has a renamed role
            sql.update("delete from role_permission where role_id = md5(?::text || ':TREASURER')::uuid "
                    + "and permission = 'AUDIT_VIEW'", first);
            sql.update("update role set name = 'Cashier', name_customized = true where id = md5(?::text || "
                    + "':TREASURER')::uuid", second);
            assertThat(permissions(sql, first, "CHAIRMAN")).hasSize(9).doesNotContain("TARIFFS_VIEW");
            assertThat(sql.queryForObject("select count(*) from information_schema.tables where table_name = "
                    + "'charge_type'", Integer.class)).isZero();

            flyway(postgres, null).migrate();

            for (UUID tenant : List.of(first, second)) {
                assertThat(permissions(sql, tenant, "CHAIRMAN")).hasSize(11)
                        .contains("TARIFFS_VIEW", "TARIFFS_MANAGE");
                assertThat(permissions(sql, tenant, "MEMBER")).isEmpty();
                assertThat(sql.queryForList("select code from charge_type where tenant_id = ? order by position",
                        String.class, tenant)).containsExactly("MEMBERSHIP", "ELECTRICITY", "LAND_TAX", "WATER");
                assertThat(sql.queryForList("select name from charge_type where tenant_id = ? order by position",
                        String.class, tenant)).containsExactly("Membership fee", "Electricity", "Land tax", "Water");
                assertThat(sql.queryForList("select active from charge_type where tenant_id = ? order by position",
                        Boolean.class, tenant)).containsExactly(true, true, true, false);
            }
            // Treasurer: only TARIFFS_VIEW is added; the permission the first chairman removed stays removed
            assertThat(permissions(sql, first, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "TARIFFS_VIEW");
            assertThat(permissions(sql, second, "TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW",
                    "AUDIT_VIEW", "TARIFFS_VIEW");
            assertThat(sql.queryForObject("select name from role where id = md5(?::text || ':TREASURER')::uuid",
                    String.class, second)).as("a renamed role keeps its name").isEqualTo("Cashier");
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
