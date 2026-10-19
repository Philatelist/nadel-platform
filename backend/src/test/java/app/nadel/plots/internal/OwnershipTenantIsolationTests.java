package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The ownership table: cooperatives are isolated, links never cross them, and the table keeps its own rules. */
class OwnershipTenantIsolationTests extends IntegrationTest {

    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000032");
    private static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String INSERT = "insert into ownership (tenant_id, plot_id, member_id, share_numerator, "
            + "share_denominator, valid_from, valid_to) values (?, ?, ?, ?, ?, ?::date, ?::date)";

    private final List<UUID> tempPlots = new java.util.ArrayList<>();

    @AfterEach
    void removeTempPlots() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : tempPlots) {
            owner.update("delete from ownership where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        tempPlots.clear();
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRows() {
        Integer demoRows = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select count(*) from ownership",
                Integer.class));
        Integer demoForeign = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from ownership where tenant_id <> ?", Integer.class, DEMO_TENANT));
        Integer otherRows = inTenant(OTHER_TENANT, sql -> sql.queryForObject("select count(*) from ownership",
                Integer.class));
        Integer otherForeign = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select count(*) from ownership where tenant_id <> ?", Integer.class, OTHER_TENANT));

        assertThat(demoRows).isEqualTo(29);
        assertThat(demoForeign).isZero();
        assertThat(otherRows).isEqualTo(1);
        assertThat(otherForeign).isZero();
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        // the application role outside a tenant transaction: no app.tenant_id, so no rows
        Integer rows = inTenant(UUID.randomUUID(), sql -> sql.queryForObject("select count(*) from ownership",
                Integer.class));

        assertThat(rows).isZero();
    }

    @Test
    void cannotInsertARowForAnotherCooperative() {
        UUID otherPlot = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '2'", UUID.class, OTHER_TENANT);

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(INSERT, OTHER_TENANT, otherPlot,
                OTHER_MEMBER, 1, 1, "2020-01-01", null)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    @Test
    void cannotUpdateRowsOfAnotherCooperative() {
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update ownership set basis = 'tampered' where tenant_id = ?", OTHER_TENANT));

        assertThat(updated).isZero();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from ownership where basis = 'tampered'", Integer.class)).isZero();
    }

    @Test
    void aMemberOfAnotherCooperativeCannotOwnAPlot() {
        UUID plot = tempPlot();

        // the foreign key is composite: (tenant_id, member_id) must exist together
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, plot, OTHER_MEMBER, 1,
                1, "2020-01-01", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("ownership_member_fk");
    }

    @Test
    void aPlotOfAnotherCooperativeCannotBeOwned() {
        UUID otherPlot = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '2'", UUID.class, OTHER_TENANT);

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, otherPlot, DEMO_MEMBER,
                1, 1, "2020-01-01", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("ownership_plot_fk");
    }

    @Test
    void theApplicationRoleCannotDeleteOrTruncateOwnership() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update("delete from ownership")))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table ownership");
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("truncate ownership");
            return null;
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table ownership");

        assertThat(ownerJdbc().queryForObject("select count(*) from ownership", Integer.class)).isEqualTo(30);
    }

    @Test
    void aPlotWithOwnershipCannotBeDeleted() {
        UUID plot = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '3'", UUID.class, DEMO_TENANT);

        assertThatThrownBy(() -> ownerJdbc().update("delete from plot where id = ?", plot))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void aMemberWhoOwnsCannotBeDeleted() {
        assertThatThrownBy(() -> ownerJdbc().update("delete from member where id = ?",
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void theSameMemberCannotOwnAPlotInOverlappingPeriods() {
        UUID plot = tempPlot();
        insert(plot, DEMO_MEMBER, 1, 1, "2020-01-01", "2020-12-31");

        // an open period starting inside the closed one, one that starts on its last day, and one fully inside
        for (String[] period : new String[][] {{"2020-06-01", null}, {"2020-12-31", null}, {"2020-03-01", "2020-04-30"}}) {
            assertThatThrownBy(() -> insert(plot, DEMO_MEMBER, 1, 2, period[0], period[1]))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"))
                    .hasMessageContaining("ownership_no_overlap_ex");
        }
    }

    @Test
    void adjacentPeriodsAndCoOwnersAreAllowed() {
        UUID plot = tempPlot();
        insert(plot, DEMO_MEMBER, 1, 2, "2020-01-01", "2020-12-31");
        // the same member again from the next day, and another member at the same time
        insert(plot, DEMO_MEMBER, 1, 2, "2021-01-01", null);
        insert(plot, UUID.fromString("aaaaaaaa-0000-0000-0000-000000000033"), 1, 2, "2020-01-01", null);

        assertThat(ownerJdbc().queryForObject("select count(*) from ownership where plot_id = ?", Integer.class, plot))
                .isEqualTo(3);
    }

    @Test
    void sharesAndPeriodsAreChecked() {
        UUID plot = tempPlot();

        record Bad(int numerator, int denominator, String from, String to) {
        }
        for (Bad bad : List.of(
                new Bad(0, 1, "2020-01-01", null),
                new Bad(3, 2, "2020-01-01", null),
                new Bad(1, 1001, "2020-01-01", null),
                new Bad(1, 0, "2020-01-01", null),
                new Bad(1, 2, "2020-05-02", "2020-05-01"))) {
            assertThatThrownBy(() -> insert(plot, DEMO_MEMBER, bad.numerator(), bad.denominator(), bad.from(),
                    bad.to()))
                    .as("%s", bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        }
        // a period of one day is fine, and so are the extreme shares
        insert(plot, DEMO_MEMBER, 1, 1000, "2020-05-01", "2020-05-01");
        insert(plot, UUID.fromString("aaaaaaaa-0000-0000-0000-000000000033"), 1000, 1000, "2020-05-01", null);
    }

    @Test
    void theBasisIsLimitedToFiveHundredCharacters() {
        UUID plot = tempPlot();
        String sql = "insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from, basis) values (?, ?, ?, 1, 1, date '2020-01-01', ?)";

        inTenant(DEMO_TENANT, jdbc -> jdbc.update(sql, DEMO_TENANT, plot, DEMO_MEMBER, "x".repeat(500)));
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, jdbc -> jdbc.update(sql, DEMO_TENANT, plot,
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000033"), "x".repeat(501))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    // ---- helpers

    /** A throw-away plot of the demo cooperative (the owner role can create it); removed after the test. */
    private UUID tempPlot() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                DEMO_TENANT, "T-" + id.toString().substring(0, 8));
        tempPlots.add(id);
        return id;
    }

    private void insert(UUID plot, UUID member, int numerator, int denominator, String from, String to) {
        inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, plot, member, numerator, denominator, from, to));
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
