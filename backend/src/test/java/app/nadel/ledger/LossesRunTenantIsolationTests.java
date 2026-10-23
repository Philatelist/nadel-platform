package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
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
 * The tables {@code losses_run} and {@code losses_run_node} (V17): cooperatives are isolated (RLS, no cooperative sees
 * nothing, nothing is written for another one), links never cross cooperatives (composite foreign keys), two booked runs
 * never charge a node for the same day (the exclusion constraint, freed by a reversal), the history changes only by the
 * reversal of a run (trigger {@code run_reversal_guard}, {@code DELETE}/{@code TRUNCATE} revoked from the application;
 * only {@code nadel_owner} with the maintenance setting may remove rows), and the columns keep their rules.
 */
class LossesRunTenantIsolationTests extends LedgerTestSupport {

    private static final String RUN = "insert into losses_run (id, tenant_id, from_date, to_date, billing_month_id, "
            + "total) values (?, ?, ?::date, ?::date, ?, 10.00)";
    private static final String NODE = "insert into losses_run_node (tenant_id, run_id, node_meter_id, node_kind, "
            + "from_date, to_date, position, outcome, consumption, children, losses) "
            + "values (?, ?, ?, 'QUARTER', ?::date, ?::date, 0, ?, 110, 100, ?)";

    private Side demo;
    private Side other;
    private UUID demoRun;
    private UUID otherRun;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        demoRun = UUID.randomUUID();
        otherRun = UUID.randomUUID();
        ownerJdbc().update(RUN, demoRun, DEMO_TENANT, "2031-01-01", "2031-01-31", demo.month());
        ownerJdbc().update(RUN, otherRun, OTHER_TENANT, "2031-01-01", "2031-01-31", other.month());
        ownerJdbc().update(NODE, DEMO_TENANT, demoRun, demo.meter(), "2031-01-01", "2031-01-31", "CHARGED", 10);
        ownerJdbc().update(NODE, OTHER_TENANT, otherRun, other.meter(), "2031-01-01", "2031-01-31", "CHARGED", 10);
    }

    @AfterEach
    void removeRuns() {
        asLedgerOwner(sql -> {
            sql.update("delete from losses_run_node where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
            sql.update("delete from losses_run where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        });
    }

    @Test
    void bothTablesHaveRowLevelSecurityAPolicyAndATenantIndex() {
        for (String table : List.of("losses_run", "losses_run_node")) {
            assertThat(ownerJdbc().queryForObject("select relrowsecurity from pg_class where oid = ?::regclass",
                    Boolean.class, "public." + table)).as(table).isTrue();
            assertThat(ownerJdbc().queryForObject("select count(*) from pg_policies where tablename = ? "
                    + "and policyname = 'tenant_isolation'", Integer.class, table)).as(table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject("select count(*) from pg_indexes where tablename = ? "
                    + "and indexdef like '%(tenant_id)'", Integer.class, table)).as(table).isEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRunsAndNodesAndNoCooperativeSeesNothing() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            for (String table : List.of("losses_run", "losses_run_node")) {
                assertThat(count(tenant, table, "")).as(table).isEqualTo(1);
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).isZero();
            }
        }
        assertThat(count(UUID.randomUUID(), "losses_run", "")).isZero();
        assertThat(count(UUID.randomUUID(), "losses_run_node", "")).isZero();
    }

    @Test
    void cannotWriteOrHandARunOrANodeToAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(RUN, UUID.randomUUID(), OTHER_TENANT, "2031-01-01", "2031-01-31",
                other.month()));
        assertRowSecurity(sql -> sql.update(NODE, OTHER_TENANT, otherRun, other.meter(), "2031-02-01", "2031-02-28",
                "CHARGED", 10));
        Integer reversed = inTenant(DEMO_TENANT, sql -> sql.update("update losses_run set status = 'REVERSED', "
                + "reversed_at = now(), reverse_reason = 'x' where id = ?", otherRun));
        assertThat(reversed).isZero();
    }

    @Test
    void aRunAndANodeCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("losses_run_billing_month_fk", sql -> sql.update(RUN, UUID.randomUUID(), DEMO_TENANT,
                "2031-01-01", "2031-01-31", other.month()));
        assertForeignKey("losses_run_node_run_fk", sql -> sql.update(NODE, DEMO_TENANT, otherRun, demo.meter(),
                "2031-02-01", "2031-02-28", "CHARGED", 10));
        assertForeignKey("losses_run_node_meter_fk", sql -> sql.update(NODE, DEMO_TENANT, demoRun, other.meter(),
                "2031-02-01", "2031-02-28", "CHARGED", 10));
    }

    @Test
    void twoBookedRunsNeverChargeANodeForTheSameDay() {
        UUID second = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> sql.update(RUN, second, DEMO_TENANT, "2031-01-15", "2031-02-15", demo.month()));

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(NODE, DEMO_TENANT, second, demo.meter(),
                "2031-01-15", "2031-02-15", "CHARGED", 10)))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"))
                .hasStackTraceContaining("losses_run_node_no_overlap_ex");
        // a node that charged nothing blocks nothing
        inTenant(DEMO_TENANT, sql -> sql.update(NODE, DEMO_TENANT, second, demo.meter(), "2031-01-15", "2031-02-15",
                "NEGATIVE", -5));
        // a reversed run frees its days
        asLedgerOwner(sql -> sql.update("delete from losses_run_node where run_id = ?", second));
        inTenant(DEMO_TENANT, sql -> sql.update("update losses_run_node set run_status = 'REVERSED' where run_id = ?",
                demoRun));
        assertThat(updated(sql -> sql.update(NODE, DEMO_TENANT, second, demo.meter(), "2031-01-15",
                "2031-02-15", "CHARGED", 10))).isEqualTo(1);
    }

    @Test
    void aRunChangesOnlyByItsReversal() {
        assertGuard(sql -> sql.update("update losses_run set total = 11.00 where id = ?", demoRun));
        assertGuard(sql -> sql.update("update losses_run_node set losses = 11 where run_id = ?", demoRun));

        assertThat(updated(sql -> sql.update("update losses_run set status = 'REVERSED', "
                + "reversed_at = now(), reverse_reason = 'Помилка' where id = ?", demoRun))).isEqualTo(1);
        assertThat(updated(sql -> sql.update("update losses_run_node set run_status = 'REVERSED' "
                + "where run_id = ?", demoRun))).isEqualTo(1);

        // never back, and the reason stays
        assertGuard(sql -> sql.update("update losses_run set status = 'BOOKED', reversed_at = null, "
                + "reverse_reason = null where id = ?", demoRun));
        assertGuard(sql -> sql.update("update losses_run set reverse_reason = 'Інша' where id = ?", demoRun));
        assertGuard(sql -> sql.update("update losses_run_node set run_status = 'BOOKED' where run_id = ?", demoRun));
    }

    @Test
    void runsAreNeverDeletedOrTruncatedButByTheOwnerInMaintenance() {
        for (String table : List.of("losses_run_node", "losses_run")) {
            assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update("delete from " + table)))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("permission denied for table " + table);
            assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
                sql.execute("truncate " + table + " cascade");
                return null;
            })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table");
            assertThatThrownBy(() -> ownerJdbc().update("delete from " + table + " where tenant_id = ?", DEMO_TENANT))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("the runs in " + table + " are history: DELETE");
            assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("delete from " + table)))
                    .hasStackTraceContaining("are history");
        }
        asLedgerOwner(sql -> {
            assertThat(sql.update("delete from losses_run_node where run_id = ?", demoRun)).isEqualTo(1);
            assertThat(sql.update("delete from losses_run where id = ?", demoRun)).isEqualTo(1);
        });
    }

    @Test
    void theColumnsKeepTheirRules() {
        assertCheck("losses_run_range_ck", sql -> sql.update(RUN, UUID.randomUUID(), DEMO_TENANT, "2031-01-31",
                "2031-01-01", demo.month()));
        assertCheck("losses_run_status_ck", sql -> sql.update("insert into losses_run (tenant_id, from_date, "
                + "to_date, billing_month_id, total, status) values (?, date '2031-01-01', date '2031-01-31', ?, 1, "
                + "'DONE')", DEMO_TENANT, demo.month()));
        assertCheck("losses_run_reversal_ck", sql -> sql.update("insert into losses_run (tenant_id, from_date, "
                + "to_date, billing_month_id, total, status, reversed_at) values (?, date '2031-01-01', "
                + "date '2031-01-31', ?, 1, 'REVERSED', now())", DEMO_TENANT, demo.month()));
        assertCheck("losses_run_total_ck", sql -> sql.update("insert into losses_run (tenant_id, from_date, "
                + "to_date, billing_month_id, total) values (?, date '2031-01-01', date '2031-01-31', ?, -1)",
                DEMO_TENANT, demo.month()));
        assertCheck("losses_run_node_outcome_ck", sql -> sql.update(NODE, DEMO_TENANT, demoRun, demo.meter(),
                "2031-03-01", "2031-03-31", "MAYBE", 10));
        assertCheck("losses_run_node_charged_ck", sql -> sql.update(NODE, DEMO_TENANT, demoRun, demo.meter(),
                "2031-03-01", "2031-03-31", "CHARGED", -10));
        assertCheck("losses_run_node_negative_ck", sql -> sql.update(NODE, DEMO_TENANT, demoRun, demo.meter(),
                "2031-03-01", "2031-03-31", "NEGATIVE", 10));
        assertCheck("losses_run_node_kind_ck", sql -> sql.update("insert into losses_run_node (tenant_id, run_id, "
                + "node_meter_id, node_kind, from_date, to_date, position, outcome) values (?, ?, ?, 'PLOT', "
                + "date '2031-03-01', date '2031-03-31', 0, 'UNKNOWN')", DEMO_TENANT, demoRun, demo.meter()));
    }

    // ---- helpers

    private int updated(Function<JdbcTemplate, Integer> work) {
        return inTenant(DEMO_TENANT, work);
    }

    private int count(UUID tenant, String table, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from " + table + " " + where,
                Integer.class));
    }

    private void assertRowSecurity(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    private void assertGuard(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("changes only by the reversal of its run");
    }

    private void assertForeignKey(String constraint, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining(constraint);
    }

    private void assertCheck(String constraint, Function<JdbcTemplate, ?> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
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
