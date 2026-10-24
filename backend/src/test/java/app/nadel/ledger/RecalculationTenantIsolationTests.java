package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
 * The recalculation tables (recalculation, recalculation_line) and the adjustments of the charge table (V19):
 * cooperatives are isolated, links never cross cooperatives, the tables are append-only for the application, and the
 * rules of an ADJUSTMENT (amount, source, target, once per recalculation) hold in the database. A tariff version that is
 * soft-deleted frees its start date.
 */
class RecalculationTenantIsolationTests extends LedgerTestSupport {

    private static final List<String> TABLES = List.of("recalculation", "recalculation_line");
    private static final String CHARGE_INSERT = "insert into charge (id, tenant_id, plot_id, charge_type_id, "
            + "billing_month_id, kind, source, amount, service_from, service_to, accrued_on, reverses_charge_id, "
            + "adjusts_charge_id, recalculation_id, reason) values (?, ?, ?, ?, ?, ?, ?, ?, date '2031-01-01', "
            + "date '2031-01-31', date '2031-01-31', ?, ?, ?, ?)";
    private static final String RECALCULATION_INSERT = "insert into recalculation (id, tenant_id, cause, reason, total, "
            + "line_count, plot_count, digest) values (?, ?, 'MANUAL', 'Area corrected', 0.00, 0, 0, 'digest')";
    private static final String LINE_INSERT = "insert into recalculation_line (tenant_id, recalculation_id, plot_id, "
            + "charge_type_id, service_from, service_to, original_charge_id, booked_amount, adjusted_so_far, "
            + "expected_amount, difference, action, position) values (?, ?, ?, ?, date '2031-01-01', "
            + "date '2031-01-31', ?, 10.00, 0.00, 12.00, 2.00, 'ADJUST', 1)";

    private final List<UUID> recalculations = new ArrayList<>();
    private Side demo;
    private Side other;
    private UUID demoCharge;
    private UUID otherCharge;
    private UUID demoRecalculation;
    private UUID otherRecalculation;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        demoCharge = insertCharge(demo, "RECTEST:demo");
        otherCharge = insertCharge(other, "RECTEST:other");
        demoRecalculation = insertRecalculation(DEMO_TENANT);
        otherRecalculation = insertRecalculation(OTHER_TENANT);
        ownerJdbc().update(LINE_INSERT, DEMO_TENANT, demoRecalculation, demo.plot(), demo.chargeType(), demoCharge);
        ownerJdbc().update(LINE_INSERT, OTHER_TENANT, otherRecalculation, other.plot(), other.chargeType(),
                otherCharge);
    }

    /** Runs before the base class clean-up (subclass @AfterEach first): the rows that point at the charges go first. */
    @AfterEach
    void removeRecalculationFixtures() {
        asLedgerOwner(sql -> {
            for (UUID id : recalculations) {
                sql.update("delete from recalculation_line where recalculation_id = ?", id);
                sql.update("delete from charge where recalculation_id = ?", id);
                sql.update("delete from recalculation where id = ?", id);
            }
        });
        recalculations.clear();
    }

    // ---- row level security

    @Test
    void bothTablesHaveRowLevelSecurityAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                    Integer.class, table)).as("tenant index on %s", table).isEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRows() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            assertThat(count(tenant, "recalculation", "where id in ('" + demoRecalculation + "', '"
                    + otherRecalculation + "')")).isEqualTo(1);
            assertThat(count(tenant, "recalculation_line", "where recalculation_id in ('" + demoRecalculation + "', '"
                    + otherRecalculation + "')")).isEqualTo(1);
            for (String table : TABLES) {
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).as(table).isZero();
            }
        }
        assertThat(count(UUID.randomUUID(), "recalculation", "")).isZero();
        assertThat(count(UUID.randomUUID(), "recalculation_line", "")).isZero();
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(RECALCULATION_INSERT, UUID.randomUUID(), OTHER_TENANT));
        assertRowSecurity(sql -> sql.update(LINE_INSERT, OTHER_TENANT, otherRecalculation, other.plot(),
                other.chargeType(), otherCharge));
    }

    @Test
    void linksNeverCrossCooperatives() {
        assertForeignKey("recalculation_line_recalculation_fk", sql -> sql.update(LINE_INSERT, DEMO_TENANT,
                otherRecalculation, demo.plot(), demo.chargeType(), demoCharge));
        assertForeignKey("recalculation_line_plot_fk", sql -> sql.update(LINE_INSERT, DEMO_TENANT, demoRecalculation,
                other.plot(), demo.chargeType(), demoCharge));
        assertForeignKey("recalculation_line_charge_type_fk", sql -> sql.update(LINE_INSERT, DEMO_TENANT,
                demoRecalculation, demo.plot(), other.chargeType(), demoCharge));
        assertForeignKey("recalculation_line_original_fk", sql -> sql.update(LINE_INSERT, DEMO_TENANT,
                demoRecalculation, demo.plot(), demo.chargeType(), otherCharge));
        assertForeignKey("charge_recalculation_fk", sql -> adjustment(sql, demo, demoCharge, otherRecalculation,
                d("1.00")));
    }

    @Test
    void theApplicationCannotChangeOrRemoveRows() {
        for (String table : TABLES) {
            assertDenied(table, sql -> sql.update("update " + table + " set tenant_id = tenant_id"));
            assertDenied(table, sql -> sql.update("delete from " + table));
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
    }

    @Test
    void theOwnerNeedsTheMaintenanceSettingToChangeRows() {
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update(
                "delete from recalculation_line where recalculation_id = ?", demoRecalculation)))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update(
                "update recalculation set reason = 'x' where id = ?", demoRecalculation)))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void aRecalculationAndItsLinesAreChecked() {
        assertCheck("recalculation_cause_ck", sql -> sql.update("insert into recalculation (tenant_id, cause, reason, "
                + "total, line_count, plot_count, digest) values (?, 'GUESS', 'r', 0, 0, 0, 'd')", DEMO_TENANT));
        assertCheck("recalculation_reason_ck", sql -> sql.update("insert into recalculation (tenant_id, cause, reason, "
                + "total, line_count, plot_count, digest) values (?, 'MANUAL', '  ', 0, 0, 0, 'd')", DEMO_TENANT));
        assertCheck("recalculation_reason_ck", sql -> sql.update("insert into recalculation (tenant_id, cause, reason, "
                + "total, line_count, plot_count, digest) values (?, 'MANUAL', ?, 0, 0, 0, 'd')", DEMO_TENANT,
                "x".repeat(501)));
        assertCheck("recalculation_line_action_ck", sql -> sql.update(LINE_INSERT.replace("'ADJUST'", "'GUESS'"),
                DEMO_TENANT, demoRecalculation, demo.plot(), demo.chargeType(), demoCharge));
    }

    // ---- adjustments in the charge table

    @Test
    void anAdjustmentIsAcceptedWithANegativeAndAPositiveAmount() {
        UUID second = insertRecalculation(DEMO_TENANT);

        inTenant(DEMO_TENANT, sql -> adjustment(sql, demo, demoCharge, demoRecalculation, d("-2.50")));
        inTenant(DEMO_TENANT, sql -> adjustment(sql, demo, demoCharge, second, d("4.17")));

        assertThat(ownerJdbc().queryForObject("select sum(amount) from charge where adjusts_charge_id = ?",
                BigDecimal.class, demoCharge)).isEqualByComparingTo("1.67");
    }

    @Test
    void anAdjustmentOfZeroIsRefused() {
        assertCheck("charge_reversal_ck", sql -> adjustment(sql, demo, demoCharge, demoRecalculation, d("0.00")));
    }

    @Test
    void anAdjustmentNeedsATargetAndATargetNeedsAnAdjustment() {
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "ADJUSTMENT", "RECALCULATION", d("1.00"), null, null,
                demoRecalculation, "Area"));
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "TARIFF", d("1.00"), null, demoCharge, null, null));
    }

    @Test
    void anAdjustmentHasTheRecalculationSourceAReasonAndARecalculation() {
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "ADJUSTMENT", "TARIFF", d("1.00"), null, demoCharge,
                demoRecalculation, "Area"));
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "ADJUSTMENT", "RECALCULATION", d("1.00"), null, demoCharge, null,
                "Area"));
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "ADJUSTMENT", "RECALCULATION", d("1.00"), null, demoCharge,
                demoRecalculation, null));
    }

    @Test
    void theRecalculationSourceIsOnlyForAdjustmentsAndReversalsThatNameTheirRecalculation() {
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "RECALCULATION", d("1.00"), null, null,
                demoRecalculation, null));
        assertCheck("charge_adjustment_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "RECALCULATION", d("-10.00"), demoCharge, null, null,
                "Area"));
        inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "RECALCULATION", d("-10.00"), demoCharge, null,
                demoRecalculation, "Area"));
    }

    @Test
    void anAdjustmentCannotTargetAnotherCooperativesCharge() {
        assertCheck("charge_target_guard", sql -> adjustment(sql, demo, otherCharge, demoRecalculation, d("1.00")));
    }

    @Test
    void anAdjustmentCannotTargetAReversalAnAdjustmentAnOpeningOrAReversedCharge() {
        UUID reversal = UUID.randomUUID();
        UUID reversed = insertCharge(demo, "RECTEST:reversed");
        ownerJdbc().update(CHARGE_INSERT, reversal, DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month(),
                "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), reversed, null, null, "Mistake");
        UUID adjustment = UUID.randomUUID();
        ownerJdbc().update(CHARGE_INSERT, adjustment, DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month(),
                "ADJUSTMENT", "RECALCULATION", d("1.00"), null, demoCharge, demoRecalculation, "Area");
        UUID opening = UUID.randomUUID();
        ownerJdbc().update(CHARGE_INSERT, opening, DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month(),
                "OPENING", "OPENING", d("5.00"), null, null, null, null);

        assertCheck("charge_target_guard", sql -> adjustment(sql, demo, reversal, demoRecalculation, d("1.00")));
        assertCheck("charge_target_guard", sql -> adjustment(sql, demo, adjustment, demoRecalculation, d("1.00")));
        assertCheck("charge_target_guard", sql -> adjustment(sql, demo, opening, demoRecalculation, d("1.00")));
        assertCheck("charge_target_guard", sql -> adjustment(sql, demo, reversed, demoRecalculation, d("1.00")));
    }

    @Test
    void aReversalCannotTargetAnAdjustmentOrAReversal() {
        UUID adjustment = UUID.randomUUID();
        ownerJdbc().update(CHARGE_INSERT, adjustment, DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month(),
                "ADJUSTMENT", "RECALCULATION", d("1.00"), null, demoCharge, demoRecalculation, "Area");
        UUID reversal = UUID.randomUUID();
        ownerJdbc().update(CHARGE_INSERT, reversal, DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month(),
                "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), demoCharge, null, null, "Mistake");

        assertCheck("charge_target_guard", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-1.00"), adjustment, null, null,
                "Undo"));
        assertCheck("charge_target_guard", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("10.00").negate(), reversal, null,
                null, "Undo"));
    }

    @Test
    void aRecalculationAdjustsAChargeOnceButTwoRecalculationsMay() {
        UUID second = insertRecalculation(DEMO_TENANT);
        inTenant(DEMO_TENANT, sql -> adjustment(sql, demo, demoCharge, demoRecalculation, d("-1.00")));

        assertThatThrownBy(() -> inTenant(DEMO_TENANT,
                sql -> adjustment(sql, demo, demoCharge, demoRecalculation, d("-2.00"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("charge_adjustment_once_uq");
        inTenant(DEMO_TENANT, sql -> adjustment(sql, demo, demoCharge, second, d("-2.00")));
    }

    // ---- tariff versions

    @Test
    void aSoftDeletedTariffVersionFreesItsStartDate() {
        String insert = "insert into tariff_version (tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, date '2026-01-01', 'PER_KWH', 'MONTHLY', 6.0000)";

        assertThatThrownBy(() -> ownerJdbc().update(insert, DEMO_TENANT, demo.tariff()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("tariff_version_valid_from_uq");

        ownerJdbc().update("update tariff_version set deleted_at = now() where id = ?", demo.version());
        ownerJdbc().update(insert, DEMO_TENANT, demo.tariff());

        assertThat(ownerJdbc().queryForObject("select count(*) from tariff_version where tariff_id = ?",
                Integer.class, demo.tariff())).isEqualTo(2);
    }

    // ---- seed

    @Test
    void onlyChairmenGetTheRecalculationPermission() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            assertThat(count(tenant, "role_permission", "where permission = 'RECALCULATION_RUN'")).isEqualTo(1);
            assertThat(count(tenant, "role_permission rp join role r on r.id = rp.role_id",
                    "where rp.permission = 'RECALCULATION_RUN' and r.base_code = 'CHAIRMAN'")).isEqualTo(1);
        }
    }

    // ---- helpers

    private UUID insertRecalculation(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update(RECALCULATION_INSERT, id, tenant);
        recalculations.add(id);
        return id;
    }

    private int adjustment(JdbcTemplate sql, Side side, UUID target, UUID recalculation, BigDecimal amount) {
        return sql.update(CHARGE_INSERT, UUID.randomUUID(), side.tenant(), side.plot(), side.chargeType(), side.month(),
                "ADJUSTMENT", "RECALCULATION", amount, null, target, recalculation, "Area corrected");
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
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

    private void assertDenied(String table, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table " + table);
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
