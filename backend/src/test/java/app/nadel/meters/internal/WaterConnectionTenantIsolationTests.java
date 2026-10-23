package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The table {@code plot_water_connection} (V16): cooperatives are isolated (RLS, no cooperative sees nothing, nothing is
 * written for another one), links never cross cooperatives (composite foreign keys), a plot is connected once on any
 * day (exclusion constraint) and has one lasting connection (partial unique index), and the table keeps its own rules
 * (the period CHECK, no DELETE or TRUNCATE for the application, the connections go with their plot). The fixtures are
 * throw-away plots and wells of both cooperatives, removed after the test as the database owner.
 */
class WaterConnectionTenantIsolationTests extends IntegrationTest {

    private static final String INSERT = "insert into plot_water_connection (tenant_id, plot_id, source_meter_id, "
            + "valid_from, valid_to) values (?, ?, ?, ?::date, ?::date)";

    /** A plot and a well of one cooperative. */
    private record Side(UUID tenant, UUID plot, UUID well) {
    }

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> meters = new ArrayList<>();

    private Side demo;
    private Side other;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        ownerJdbc().update(INSERT, DEMO_TENANT, demo.plot(), demo.well(), "2026-01-01", null);
        ownerJdbc().update(INSERT, OTHER_TENANT, other.plot(), null, "2026-01-01", null);
    }

    @AfterEach
    void removeFixtures() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : plots) {
            owner.update("delete from plot_water_connection where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        for (UUID meter : meters) {
            owner.update("delete from meter where id = ?", meter);
        }
        plots.clear();
        meters.clear();
    }

    private Side side(UUID tenant) {
        return new Side(tenant, plot(tenant), well(tenant));
    }

    private UUID plot(UUID tenant) {
        UUID plot = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                plot, tenant, "W-" + plot.toString().substring(0, 8));
        plots.add(plot);
        return plot;
    }

    private UUID well(UUID tenant) {
        UUID well = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, "
                + "initial_value) values (?, ?, 'COMMON_CONSUMER', 'ELECTRICITY', ?, ?, date '2026-01-01', 0)", well,
                tenant, "WI-" + well.toString().substring(0, 8), "Well " + well);
        meters.add(well);
        return well;
    }

    private int insert(JdbcTemplate sql, UUID tenant, UUID plot, UUID well, String from, String to) {
        return sql.update(INSERT, tenant, plot, well, from, to);
    }

    @Test
    void theTableHasRowLevelSecurityAPolicyAndATenantIndex() {
        assertThat(ownerJdbc().queryForObject("select relrowsecurity from pg_class "
                + "where oid = 'public.plot_water_connection'::regclass", Boolean.class)).isTrue();
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_policies "
                + "where tablename = 'plot_water_connection' and policyname = 'tenant_isolation'", Integer.class))
                .isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_indexes "
                + "where tablename = 'plot_water_connection' and indexdef like '%(tenant_id)'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnConnectionsAndNoCooperativeSeesNothing() {
        for (Side side : List.of(demo, other)) {
            assertThat(count(side.tenant(), "where plot_id in ('" + demo.plot() + "', '" + other.plot() + "')"))
                    .isEqualTo(1);
            assertThat(count(side.tenant(), "where tenant_id <> '" + side.tenant() + "'")).isZero();
        }
        assertThat(count(UUID.randomUUID(), "")).isZero();
    }

    @Test
    void cannotInsertUpdateOrHandAConnectionToAnotherCooperative() {
        UUID free = plot(OTHER_TENANT);
        assertRowSecurity(sql -> insert(sql, OTHER_TENANT, free, null, "2026-01-01", null));
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update plot_water_connection set valid_to = date '2026-05-01' where plot_id = ?", other.plot()));
        assertThat(updated).isZero();
        assertRowSecurity(sql -> sql.update("update plot_water_connection set tenant_id = ? where plot_id = ?",
                OTHER_TENANT, demo.plot()));
    }

    @Test
    void aConnectionCannotLinkRowsOfAnotherCooperative() {
        UUID free = plot(DEMO_TENANT);
        assertForeignKey("plot_water_connection_plot_fk",
                sql -> insert(sql, DEMO_TENANT, other.plot(), null, "2026-01-01", null));
        assertForeignKey("plot_water_connection_source_fk",
                sql -> insert(sql, DEMO_TENANT, free, other.well(), "2026-01-01", null));
    }

    @Test
    void aPlotIsConnectedOnceOnAnyDayAndHasOneLastingConnection() {
        inTenant(DEMO_TENANT, sql -> sql.update("update plot_water_connection set valid_to = date '2026-06-30' "
                + "where plot_id = ?", demo.plot()));
        assertViolation("23P01", "plot_water_connection_no_overlap_ex",
                sql -> insert(sql, DEMO_TENANT, demo.plot(), null, "2026-06-30", "2026-07-31"));
        inTenant(DEMO_TENANT, sql -> insert(sql, DEMO_TENANT, demo.plot(), null, "2026-07-01", null));

        // two lasting rows always share their days: the exclusion constraint or the partial unique index refuses the
        // second one, whichever PostgreSQL checks first; the index names the rule "one lasting connection" on its own
        assertThatThrownBy(() -> inTenant(DEMO_TENANT,
                sql -> insert(sql, DEMO_TENANT, demo.plot(), null, "2027-01-01", null)))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isIn("23P01", "23505"));
        assertThat(ownerJdbc().queryForObject("select indexdef from pg_indexes "
                + "where indexname = 'plot_water_connection_active_uq'", String.class))
                .contains("UNIQUE INDEX").contains("(tenant_id, plot_id)").contains("WHERE (valid_to IS NULL)");
        assertThat(count(DEMO_TENANT, "where plot_id = '" + demo.plot() + "'")).isEqualTo(2);
        // the other cooperative's plot and another plot are no clash
        inTenant(DEMO_TENANT, sql -> insert(sql, DEMO_TENANT, plot(DEMO_TENANT), null, "2026-01-01", null));
    }

    @Test
    void thePeriodEndsOnOrAfterItsFirstDay() {
        UUID plot = plot(DEMO_TENANT);
        assertThatThrownBy(() -> inTenant(DEMO_TENANT,
                sql -> insert(sql, DEMO_TENANT, plot, null, "2026-05-01", "2026-04-30")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining("plot_water_connection_period_ck");
    }

    @Test
    void theApplicationRoleUpdatesButNeverDeletesOrTruncates() {
        assertDenied(sql -> sql.update("delete from plot_water_connection where plot_id = ?", demo.plot()));
        assertDenied(sql -> {
            sql.execute("truncate plot_water_connection");
            return null;
        });
        assertThat(count(DEMO_TENANT, "where plot_id = '" + demo.plot() + "'")).isEqualTo(1);
    }

    @Test
    void theConnectionsGoWithTheirPlot() {
        inTenant(DEMO_TENANT, sql -> sql.update("delete from plot where id = ?", demo.plot()));

        assertThat(count(DEMO_TENANT, "where plot_id = '" + demo.plot() + "'")).isZero();
    }

    // ---- helpers

    private int count(UUID tenant, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from plot_water_connection " + where,
                Integer.class));
    }

    private void assertRowSecurity(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    private void assertForeignKey(String constraint, Function<JdbcTemplate, Object> statement) {
        assertViolation("23503", constraint, statement);
    }

    private void assertViolation(String state, String constraint, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(state))
                .hasStackTraceContaining(constraint);
    }

    private void assertDenied(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table plot_water_connection");
    }

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
