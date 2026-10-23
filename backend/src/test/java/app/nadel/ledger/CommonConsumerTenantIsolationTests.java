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
 * The tables of the common consumer runs (V18): {@code common_consumer_run} and {@code common_consumer_run_line} (the
 * history) and {@code common_consumer_billing} and {@code common_consumer_user} (the settings of each consumer).
 * Cooperatives are isolated (RLS, no cooperative sees nothing, nothing is written for another one), links never cross
 * cooperatives (composite foreign keys), two booked runs never charge a consumer for the same day (the exclusion
 * constraint, freed by a reversal), the history changes only by the reversal of a run (trigger
 * {@code run_reversal_guard}, {@code DELETE}/{@code TRUNCATE} revoked from the application; only {@code nadel_owner}
 * with the maintenance setting may remove rows), the settings may change and go with their meter, and the columns keep
 * their rules.
 */
class CommonConsumerTenantIsolationTests extends LedgerTestSupport {

    private static final List<String> TABLES = List.of("common_consumer_run", "common_consumer_run_line",
            "common_consumer_billing", "common_consumer_user");
    private static final String RUN = "insert into common_consumer_run (id, tenant_id, from_date, to_date, "
            + "billing_month_id, total) values (?, ?, ?::date, ?::date, ?, 10.00)";
    private static final String LINE = "insert into common_consumer_run_line (tenant_id, run_id, consumer_meter_id, "
            + "from_date, to_date, position, split, outcome, consumption) "
            + "values (?, ?, ?, ?::date, ?::date, 0, 'EQUAL_PER_PLOT', ?, ?)";
    private static final String BILLING = "insert into common_consumer_billing (tenant_id, consumer_meter_id, split) "
            + "values (?, ?, 'SERVICE_USERS')";
    private static final String USER = "insert into common_consumer_user (tenant_id, consumer_meter_id, plot_id) "
            + "values (?, ?, ?)";

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
        ownerJdbc().update(LINE, DEMO_TENANT, demoRun, demo.meter(), "2031-01-01", "2031-01-31", "CHARGED", 10);
        ownerJdbc().update(LINE, OTHER_TENANT, otherRun, other.meter(), "2031-01-01", "2031-01-31", "CHARGED", 10);
        ownerJdbc().update(BILLING, DEMO_TENANT, demo.meter());
        ownerJdbc().update(BILLING, OTHER_TENANT, other.meter());
        ownerJdbc().update(USER, DEMO_TENANT, demo.meter(), demo.plot());
        ownerJdbc().update(USER, OTHER_TENANT, other.meter(), other.plot());
    }

    @AfterEach
    void removeRuns() {
        asLedgerOwner(sql -> {
            sql.update("delete from common_consumer_run_line where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
            sql.update("delete from common_consumer_run where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        });
        // the settings go with the meters and plots the fixtures remove
    }

    @Test
    void everyTableHasRowLevelSecurityAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject("select relrowsecurity from pg_class where oid = ?::regclass",
                    Boolean.class, "public." + table)).as(table).isTrue();
            assertThat(ownerJdbc().queryForObject("select count(*) from pg_policies where tablename = ? "
                    + "and policyname = 'tenant_isolation'", Integer.class, table)).as(table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject("select count(*) from pg_indexes where tablename = ? "
                    + "and indexdef like '%(tenant_id)'", Integer.class, table)).as(table).isEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRowsAndNoCooperativeSeesNothing() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            for (String table : TABLES) {
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).as(table).isZero();
            }
        }
        for (String table : List.of("common_consumer_run", "common_consumer_run_line")) {
            assertThat(count(DEMO_TENANT, table, "")).as(table).isEqualTo(1);
        }
        assertThat(count(DEMO_TENANT, "common_consumer_billing", "where consumer_meter_id = '" + demo.meter() + "'"))
                .isEqualTo(1);
        assertThat(count(DEMO_TENANT, "common_consumer_user", "where plot_id = '" + demo.plot() + "'")).isEqualTo(1);
        assertThat(count(DEMO_TENANT, "common_consumer_user", "where plot_id = '" + other.plot() + "'")).isZero();
        for (String table : TABLES) {
            assertThat(count(UUID.randomUUID(), table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotWriteOrHandRowsToAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(RUN, UUID.randomUUID(), OTHER_TENANT, "2031-01-01", "2031-01-31",
                other.month()));
        assertRowSecurity(sql -> sql.update(LINE, OTHER_TENANT, otherRun, other.meter(), "2031-02-01", "2031-02-28",
                "CHARGED", 10));
        assertRowSecurity(sql -> sql.update("insert into common_consumer_billing (tenant_id, consumer_meter_id, "
                + "split) values (?, ?, 'NOT_CHARGED')", OTHER_TENANT, other.plot()));
        assertRowSecurity(sql -> sql.update(USER, OTHER_TENANT, other.meter(), other.plot()));
        assertThat(updated(sql -> sql.update("update common_consumer_run set status = 'REVERSED', "
                + "reversed_at = now(), reverse_reason = 'x' where id = ?", otherRun))).isZero();
        assertThat(updated(sql -> sql.update("update common_consumer_billing set split = 'NOT_CHARGED' "
                + "where consumer_meter_id = ?", other.meter()))).isZero();
        assertThat(updated(sql -> sql.update("delete from common_consumer_user where consumer_meter_id = ?",
                other.meter()))).isZero();
    }

    @Test
    void rowsCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("common_consumer_run_billing_month_fk", sql -> sql.update(RUN, UUID.randomUUID(),
                DEMO_TENANT, "2031-01-01", "2031-01-31", other.month()));
        assertForeignKey("common_consumer_run_line_run_fk", sql -> sql.update(LINE, DEMO_TENANT, otherRun,
                demo.meter(), "2031-02-01", "2031-02-28", "CHARGED", 10));
        assertForeignKey("common_consumer_billing_meter_fk", sql -> sql.update("insert into common_consumer_billing "
                + "(tenant_id, consumer_meter_id, split) values (?, ?, 'NOT_CHARGED')", DEMO_TENANT, other.meter()));
        assertForeignKey("common_consumer_user_meter_fk", sql -> sql.update(USER, DEMO_TENANT, other.meter(),
                demo.plot()));
        assertForeignKey("common_consumer_user_plot_fk", sql -> sql.update(USER, DEMO_TENANT, demo.meter(),
                other.plot()));
    }

    @Test
    void twoBookedRunsNeverChargeAConsumerForTheSameDay() {
        UUID second = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> sql.update(RUN, second, DEMO_TENANT, "2031-01-15", "2031-02-15", demo.month()));

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(LINE, DEMO_TENANT, second, demo.meter(),
                "2031-01-15", "2031-02-15", "CHARGED", 10)))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"))
                .hasStackTraceContaining("common_consumer_run_line_no_overlap_ex");
        // a consumer that charged nothing blocks nothing
        inTenant(DEMO_TENANT, sql -> sql.update(LINE, DEMO_TENANT, second, demo.meter(), "2031-01-15", "2031-02-15",
                "NO_PLOTS", 10));
        // a reversed run frees its days
        asLedgerOwner(sql -> sql.update("delete from common_consumer_run_line where run_id = ?", second));
        inTenant(DEMO_TENANT, sql -> sql.update("update common_consumer_run_line set run_status = 'REVERSED' "
                + "where run_id = ?", demoRun));
        assertThat(updated(sql -> sql.update(LINE, DEMO_TENANT, second, demo.meter(), "2031-01-15", "2031-02-15",
                "CHARGED", 10))).isEqualTo(1);
    }

    @Test
    void aRunChangesOnlyByItsReversal() {
        assertGuard(sql -> sql.update("update common_consumer_run set total = 11.00 where id = ?", demoRun));
        assertGuard(sql -> sql.update("update common_consumer_run_line set consumption = 11 where run_id = ?",
                demoRun));

        assertThat(updated(sql -> sql.update("update common_consumer_run set status = 'REVERSED', "
                + "reversed_at = now(), reverse_reason = 'Помилка' where id = ?", demoRun))).isEqualTo(1);
        assertThat(updated(sql -> sql.update("update common_consumer_run_line set run_status = 'REVERSED' "
                + "where run_id = ?", demoRun))).isEqualTo(1);

        // never back, and the reason stays
        assertGuard(sql -> sql.update("update common_consumer_run set status = 'BOOKED', reversed_at = null, "
                + "reverse_reason = null where id = ?", demoRun));
        assertGuard(sql -> sql.update("update common_consumer_run set reverse_reason = 'Інша' where id = ?",
                demoRun));
        assertGuard(sql -> sql.update("update common_consumer_run_line set run_status = 'BOOKED' where run_id = ?",
                demoRun));
    }

    @Test
    void runsAreNeverDeletedOrTruncatedButByTheOwnerInMaintenance() {
        for (String table : List.of("common_consumer_run_line", "common_consumer_run")) {
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
            assertThat(sql.update("delete from common_consumer_run_line where run_id = ?", demoRun)).isEqualTo(1);
            assertThat(sql.update("delete from common_consumer_run where id = ?", demoRun)).isEqualTo(1);
        });
    }

    @Test
    void theSettingsChangeAndGoWithTheirMeterButAreNeverTruncated() {
        assertThat(updated(sql -> sql.update("update common_consumer_billing set split = 'NOT_CHARGED' "
                + "where consumer_meter_id = ?", demo.meter()))).isEqualTo(1);
        assertThat(updated(sql -> sql.update("delete from common_consumer_user where consumer_meter_id = ?",
                demo.meter()))).isEqualTo(1);
        assertThat(updated(sql -> sql.update(USER, DEMO_TENANT, demo.meter(), demo.plot()))).isEqualTo(1);
        for (String table : List.of("common_consumer_billing", "common_consumer_user")) {
            assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
                sql.execute("truncate " + table);
                return null;
            })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table");
        }
        // a consumer meter that is deleted takes its settings with it (the run line keeps no link to it)
        ownerJdbc().update("delete from meter where id = ?", other.meter());
        assertThat(ownerJdbc().queryForObject("select count(*) from common_consumer_billing where consumer_meter_id "
                + "= ?", Integer.class, other.meter())).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from common_consumer_user where consumer_meter_id "
                + "= ?", Integer.class, other.meter())).isZero();
    }

    @Test
    void theColumnsKeepTheirRules() {
        assertCheck("common_consumer_run_range_ck", sql -> sql.update(RUN, UUID.randomUUID(), DEMO_TENANT,
                "2031-01-31", "2031-01-01", demo.month()));
        assertCheck("common_consumer_run_status_ck", sql -> sql.update("insert into common_consumer_run (tenant_id, "
                + "from_date, to_date, billing_month_id, total, status) values (?, date '2031-01-01', "
                + "date '2031-01-31', ?, 1, 'DONE')", DEMO_TENANT, demo.month()));
        assertCheck("common_consumer_run_reversal_ck", sql -> sql.update("insert into common_consumer_run "
                + "(tenant_id, from_date, to_date, billing_month_id, total, status, reversed_at) values (?, "
                + "date '2031-01-01', date '2031-01-31', ?, 1, 'REVERSED', now())", DEMO_TENANT, demo.month()));
        assertCheck("common_consumer_run_total_ck", sql -> sql.update("insert into common_consumer_run (tenant_id, "
                + "from_date, to_date, billing_month_id, total) values (?, date '2031-01-01', date '2031-01-31', ?, "
                + "-1)", DEMO_TENANT, demo.month()));
        assertCheck("common_consumer_run_line_outcome_ck", sql -> sql.update(LINE, DEMO_TENANT, demoRun,
                UUID.randomUUID(), "2031-03-01", "2031-03-31", "MAYBE", 10));
        assertCheck("common_consumer_run_line_charged_ck", sql -> sql.update("insert into common_consumer_run_line "
                + "(tenant_id, run_id, consumer_meter_id, from_date, to_date, position, split, outcome) values (?, ?, "
                + "?, date '2031-03-01', date '2031-03-31', 0, 'EQUAL_PER_PLOT', 'CHARGED')", DEMO_TENANT, demoRun,
                UUID.randomUUID()));
        assertCheck("common_consumer_run_line_unknown_ck", sql -> sql.update(LINE, DEMO_TENANT, demoRun,
                UUID.randomUUID(), "2031-03-01", "2031-03-31", "UNKNOWN", 10));
        assertCheck("common_consumer_run_line_consumption_ck", sql -> sql.update(LINE, DEMO_TENANT, demoRun,
                UUID.randomUUID(), "2031-03-01", "2031-03-31", "NO_PLOTS", -1));
        assertCheck("common_consumer_run_line_split_ck", sql -> sql.update("insert into common_consumer_run_line "
                + "(tenant_id, run_id, consumer_meter_id, from_date, to_date, position, split, outcome) values (?, ?, "
                + "?, date '2031-03-01', date '2031-03-31', 0, 'RANDOM', 'UNKNOWN')", DEMO_TENANT, demoRun,
                UUID.randomUUID()));
        assertCheck("common_consumer_run_line_amount_ck", sql -> sql.update("insert into common_consumer_run_line "
                + "(tenant_id, run_id, consumer_meter_id, from_date, to_date, position, split, outcome, amount) "
                + "values (?, ?, ?, date '2031-03-01', date '2031-03-31', 0, 'NOT_CHARGED', 'NOT_CHARGED', 5)",
                DEMO_TENANT, demoRun, UUID.randomUUID()));
        assertCheck("common_consumer_billing_split_ck", sql -> sql.update("update common_consumer_billing "
                + "set split = 'SOMETIMES' where consumer_meter_id = ?", demo.meter()));
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
