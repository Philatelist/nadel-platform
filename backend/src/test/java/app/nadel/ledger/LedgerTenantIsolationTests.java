package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The four ledger tables (billing_month, charge, charge_part, meter_value_use): cooperatives are isolated (RLS, no
 * cooperative sees nothing, nothing is written for another one), links never cross cooperatives (composite foreign
 * keys), and the tables keep their own rules (CHECKs, partial unique indexes, grants). The fixtures are throw-away rows
 * of both cooperatives that the base class removes.
 */
class LedgerTenantIsolationTests extends LedgerTestSupport {

    private static final List<String> TABLES = List.of("billing_month", "charge", "charge_part", "meter_value_use");
    private static final String CHARGE_INSERT = "insert into charge (id, tenant_id, plot_id, charge_type_id, "
            + "billing_month_id, kind, source, amount, service_from, service_to, accrued_on, source_key, tariff_id, "
            + "meter_id, reverses_charge_id, reason) values (?, ?, ?, ?, ?, ?, ?, ?, date '2031-01-01', "
            + "date '2031-01-31', date '2031-01-31', ?, ?, ?, ?, ?)";
    private static final String PART_INSERT = "insert into charge_part (tenant_id, charge_id, tariff_version_id, days, "
            + "units_num, units_den, rate, node_meter_id) values (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String USE_INSERT = "insert into meter_value_use (tenant_id, meter_id, used_on, charge_id, "
            + "run_id) values (?, ?, date '2031-01-15', ?, ?)";

    private Side demo;
    private Side other;
    private UUID demoCharge;
    private UUID otherCharge;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        demoCharge = insertCharge(demo, "ISOTEST:demo");
        otherCharge = insertCharge(other, "ISOTEST:other");
        insertPart(demo, demoCharge);
        insertPart(other, otherCharge);
        ownerJdbc().update(USE_INSERT, DEMO_TENANT, demo.meter(), demoCharge, null);
        ownerJdbc().update(USE_INSERT, OTHER_TENANT, other.meter(), otherCharge, null);
    }

    // ---- row level security

    @Test
    void everyTableHasRowLevelSecurityForcedByAPolicy() {
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
            Side own = tenant.equals(DEMO_TENANT) ? demo : other;
            assertThat(count(tenant, "billing_month", "where id in ('" + demo.month() + "', '" + other.month() + "')"))
                    .isEqualTo(1);
            assertThat(count(tenant, "charge", "where plot_id in ('" + demo.plot() + "', '" + other.plot() + "')"))
                    .isEqualTo(1);
            assertThat(count(tenant, "charge", "where id = '" + own.tenant() + "'")).isZero();
            assertThat(count(tenant, "charge_part", "where tariff_version_id in ('" + demo.version() + "', '"
                    + other.version() + "')")).isEqualTo(1);
            assertThat(count(tenant, "meter_value_use", "where meter_id in ('" + demo.meter() + "', '" + other.meter()
                    + "')")).isEqualTo(1);
            for (String table : TABLES) {
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).as(table).isZero();
            }
        }
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID nobody = UUID.randomUUID();

        for (String table : TABLES) {
            assertThat(count(nobody, table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        assertRowSecurity(sql -> sql.update("insert into billing_month (tenant_id, month) values (?, date '2031-02-01')",
                OTHER_TENANT));
        assertRowSecurity(sql -> charge(sql, UUID.randomUUID(), other, "REGULAR", "TARIFF", d("10.00"), "K1", null, null));
        assertRowSecurity(sql -> sql.update(PART_INSERT, OTHER_TENANT, otherCharge, other.version(), 1, 1, 1, d("1.0000"),
                null));
        assertRowSecurity(sql -> sql.update(USE_INSERT, OTHER_TENANT, other.meter(), otherCharge, null));
        assertThat(ownerJdbc().queryForObject("select count(*) from charge where source_key = 'K1'", Integer.class))
                .isZero();
    }

    @Test
    void cannotUpdateOrDeleteRowsOfAnotherCooperative() {
        // billing_month is the table nadel_app may update; the ledger tables refuse it by privilege before RLS
        Integer months = inTenant(DEMO_TENANT, sql -> sql.update(
                "update billing_month set run_at = now() where tenant_id = ?", OTHER_TENANT));
        Integer uses = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_value_use where tenant_id = ?",
                OTHER_TENANT));

        assertThat(months).isZero();
        assertThat(uses).isZero();
        assertThat(count(OTHER_TENANT, "meter_value_use", "where meter_id = '" + other.meter() + "'")).isEqualTo(1);
    }

    @Test
    void cannotHandARowToAnotherCooperative() {
        assertRowSecurity(sql -> sql.update("update billing_month set tenant_id = ? where id = ?", OTHER_TENANT,
                demo.month()));
        assertRowSecurity(sql -> sql.update("update meter_value_use set tenant_id = ? where meter_id = ?",
                OTHER_TENANT, demo.meter()));
    }

    // ---- composite foreign keys

    @Test
    void aChargeCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("charge_plot_fk", sql -> charge(sql, UUID.randomUUID(), withPlot(demo, other.plot()),
                "REGULAR", "TARIFF", d("10.00"), "FK1", null, null));
        assertForeignKey("charge_charge_type_fk", sql -> charge(sql, UUID.randomUUID(),
                withChargeType(demo, other.chargeType()), "REGULAR", "TARIFF", d("10.00"), "FK2", null, null));
        assertForeignKey("charge_billing_month_fk", sql -> charge(sql, UUID.randomUUID(),
                withMonth(demo, other.month()), "REGULAR", "TARIFF", d("10.00"), "FK3", null, null));
        assertForeignKey("charge_tariff_fk", sql -> charge(sql, UUID.randomUUID(), withTariff(demo, other.tariff()),
                "REGULAR", "TARIFF", d("10.00"), "FK4", null, null));
        assertForeignKey("charge_meter_fk", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "CONSUMPTION", d("10.00"), "FK5", null, other.meter(), null,
                null));
        assertForeignKey("charge_reverses_fk", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null,
                otherCharge, "Wrong"));
    }

    @Test
    void aChargePartAndAUseCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("charge_part_charge_fk", sql -> sql.update(PART_INSERT, DEMO_TENANT, otherCharge,
                demo.version(), 1, 1, 1, d("1.0000"), null));
        assertForeignKey("charge_part_tariff_version_fk", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge,
                other.version(), 1, 1, 1, d("1.0000"), null));
        assertForeignKey("charge_part_node_meter_fk", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge,
                demo.version(), 1, 1, 1, d("1.0000"), other.meter()));
        assertForeignKey("meter_value_use_meter_fk", sql -> sql.update(USE_INSERT, DEMO_TENANT, other.meter(),
                demoCharge, null));
        assertForeignKey("meter_value_use_charge_fk", sql -> sql.update(USE_INSERT, DEMO_TENANT, demo.meter(),
                otherCharge, null));
    }

    @Test
    void aBillingMonthCannotBeClosedByAMemberOfAnotherCooperative() {
        UUID otherMember = newMember(OTHER_TENANT, "isotest-other@ledger.local");

        assertForeignKey("billing_month_closed_by_fk", sql -> sql.update(
                "insert into billing_month (tenant_id, month, status, closed_at, closed_by) "
                        + "values (?, date '2031-02-01', 'CLOSED', now(), ?)", DEMO_TENANT, otherMember));
    }

    // ---- billing_month

    @Test
    void aMonthIsUniquePerCooperativeAndStartsOnTheFirstDay() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "insert into billing_month (tenant_id, month) values (?, date '" + MONTH + "')", DEMO_TENANT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("billing_month_tenant_month_uq");
        assertCheck("billing_month_first_day_ck", sql -> sql.update(
                "insert into billing_month (tenant_id, month) values (?, date '2031-02-02')", DEMO_TENANT));
    }

    @Test
    void theStatusAndTheClosingTimeComeTogether() {
        assertCheck("billing_month_status_ck", sql -> sql.update(
                "insert into billing_month (tenant_id, month, status) values (?, date '2031-02-01', 'LOCKED')",
                DEMO_TENANT));
        assertCheck("billing_month_closed_ck", sql -> sql.update(
                "insert into billing_month (tenant_id, month, status) values (?, date '2031-02-01', 'CLOSED')",
                DEMO_TENANT));
        assertCheck("billing_month_closed_ck", sql -> sql.update(
                "insert into billing_month (tenant_id, month, status, closed_at) "
                        + "values (?, date '2031-02-01', 'OPEN', now())", DEMO_TENANT));
        inTenant(DEMO_TENANT, sql -> sql.update(
                "insert into billing_month (tenant_id, month, status, closed_at) "
                        + "values (?, date '2031-02-01', 'CLOSED', now())", DEMO_TENANT));
        // billing_month refuses DELETE (trigger billing_month_guard, V14) except to the owner in maintenance
        asLedgerOwner(sql -> sql.update("delete from billing_month where tenant_id = ? and month = date '2031-02-01'",
                DEMO_TENANT));
    }

    // ---- charge checks and unique indexes

    @Test
    void aReversalIsNegativeWithAReasonAndAnOriginalAndOtherKindsArePositive() {
        // reversal needs original, reason and a negative amount; both directions
        assertCheck("charge_reversal_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null, demoCharge,
                null));
        assertCheck("charge_reversal_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null, null,
                "Wrong"));
        assertCheck("charge_reversal_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("10.00"), null, null, null, demoCharge,
                "Wrong"));
        assertCheck("charge_reversal_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "TARIFF", d("-10.00"), "NEG", null, null, null, null));
        assertCheck("charge_reversal_ck", sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "TARIFF", d("0.00"), "ZERO", null, null, null, null));
        // any other kind may carry an optional reason (an opening balance's reason or document reference)
        inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REGULAR", "TARIFF", d("10.00"), "RSN", null, null, null, "Reason"));
        inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null, demoCharge,
                "Wrong tariff"));
        assertThat(count(DEMO_TENANT, "charge", "where reverses_charge_id = '" + demoCharge + "'")).isEqualTo(1);
    }

    @Test
    void aChargeIsReversedAtMostOnce() {
        inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null, demoCharge,
                "First"));

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), null, null, null,
                demoCharge, "Second")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("charge_reversed_once_uq");
    }

    @Test
    void aSourceKeyIsUniqueAmongNonReversalsAndNullKeysNeverClash() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> charge(sql, UUID.randomUUID(), demo, "REGULAR", "TARIFF",
                d("10.00"), "ISOTEST:demo", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("charge_source_key_uq");

        // the same key in another cooperative, and several rows without a key, are fine
        inTenant(OTHER_TENANT, sql -> charge(sql, UUID.randomUUID(), other, "REGULAR", "TARIFF", d("10.00"),
                "ISOTEST:demo", null, null));
        inTenant(DEMO_TENANT, sql -> charge(sql, UUID.randomUUID(), demo, "REGULAR", "TARIFF", d("10.00"), null, null,
                null));
        inTenant(DEMO_TENANT, sql -> charge(sql, UUID.randomUUID(), demo, "REGULAR", "TARIFF", d("10.00"), null, null,
                null));
        // a reversal is exempt from the key rule
        inTenant(DEMO_TENANT, sql -> sql.update(CHARGE_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month(), "REVERSAL", "MANUAL_REVERSAL", d("-10.00"), "ISOTEST:demo", null, null,
                demoCharge, "Same key"));
    }

    @Test
    void openingChargesHaveTheOpeningSourceAndOnlyThey() {
        assertCheck("charge_opening_ck", sql -> charge(sql, UUID.randomUUID(), demo, "OPENING", "TARIFF", d("10.00"),
                "OP1", null, null));
        assertCheck("charge_opening_ck", sql -> charge(sql, UUID.randomUUID(), demo, "REGULAR", "OPENING", d("10.00"),
                "OP2", null, null));
        inTenant(DEMO_TENANT, sql -> charge(sql, UUID.randomUUID(), demo, "OPENING", "OPENING", d("10.00"), "OP3", null,
                null));
    }

    @Test
    void kindAndSourceAreChecked() {
        assertCheck("charge_kind_ck", sql -> charge(sql, UUID.randomUUID(), demo, "BONUS", "TARIFF", d("10.00"), "KS1",
                null, null));
        assertCheck("charge_source_ck", sql -> charge(sql, UUID.randomUUID(), demo, "REGULAR", "MAGIC", d("10.00"), "KS2",
                null, null));
    }

    @Test
    void theServicePeriodDoesNotEndBeforeItStartsAndAnAmountHasTwoDecimals() {
        assertCheck("charge_service_period_ck", sql -> sql.update("insert into charge (tenant_id, plot_id, "
                + "charge_type_id, billing_month_id, kind, source, amount, service_from, service_to, accrued_on) "
                + "values (?, ?, ?, ?, 'REGULAR', 'TARIFF', 1.00, date '2031-01-31', date '2031-01-30', "
                + "date '2031-01-31')", DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month()));
        UUID id = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> charge(sql, id, demo, "REGULAR", "TARIFF", d("10.005"), "ROUND", null, null));
        assertThat(ownerJdbc().queryForObject("select amount::text from charge where id = ?", String.class, id))
                .isEqualTo("10.01");
    }

    // ---- charge_part and meter_value_use

    @Test
    void aChargePartKeepsAnExactFractionAndSensibleNumbers() {
        assertCheck("charge_part_units_ck", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge, demo.version(), 1, 1,
                0, d("1.0000"), null));
        assertCheck("charge_part_units_ck", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge, demo.version(), 1,
                -1, 1, d("1.0000"), null));
        assertCheck("charge_part_days_ck", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge, demo.version(), -1, 1,
                1, d("1.0000"), null));
        assertCheck("charge_part_rate_ck", sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge, demo.version(), 1, 1,
                1, d("-0.0001"), null));
        inTenant(DEMO_TENANT, sql -> sql.update(PART_INSERT, DEMO_TENANT, demoCharge, demo.version(), 10, 1, 3,
                d("5.2500"), demo.meter()));
        assertThat(count(DEMO_TENANT, "charge_part", "where charge_id = '" + demoCharge + "'")).isEqualTo(2);
    }

    @Test
    void aValueUseBelongsToExactlyOneOfAChargeAndARun() {
        assertCheck("meter_value_use_owner_ck", sql -> sql.update(USE_INSERT, DEMO_TENANT, demo.meter(), null, null));
        assertCheck("meter_value_use_owner_ck", sql -> sql.update(USE_INSERT, DEMO_TENANT, demo.meter(), demoCharge,
                UUID.randomUUID()));
        inTenant(DEMO_TENANT, sql -> sql.update(USE_INSERT, DEMO_TENANT, demo.meter(), null, UUID.randomUUID()));
    }

    // ---- grants

    @Test
    void theApplicationRoleCannotTruncateAnyOfTheTables() {
        for (String table : TABLES) {
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
    }

    @Test
    void valueUseRowsAreDeletableAndMonthsAreNot() {
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_value_use where meter_id = ?",
                demo.meter()));
        assertThat(deleted).isEqualTo(1);
        assertDenied("billing_month", sql -> sql.update("delete from billing_month where id = ?", demo.month()));
    }

    // ---- seeds

    @Test
    void everyCooperativeHasTheCommonConsumersTypeAndTheNewPermissions() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            assertThat(count(tenant, "charge_type", "where code = 'COMMON_CONSUMERS' and position = 5 and active"))
                    .isEqualTo(1);
            assertThat(count(tenant, "role_permission", "where permission = 'CHARGES_VIEW'")).isEqualTo(2);
            assertThat(count(tenant, "role_permission", "where permission in ('CHARGES_RUN', 'CHARGES_REVERSE', "
                    + "'PERIODS_CLOSE')")).isEqualTo(3);
        }
    }

    // ---- helpers

    private int charge(JdbcTemplate sql, UUID id, Side side, String kind, String source, BigDecimal amount, String key,
            UUID meter, UUID reverses) {
        return sql.update(CHARGE_INSERT, id, side.tenant(), side.plot(), side.chargeType(), side.month(), kind, source,
                amount, key, side.tariff(), meter, reverses, null);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Side withPlot(Side s, UUID plot) {
        return new Side(s.tenant(), plot, s.meter(), s.chargeType(), s.month(), s.tariff(), s.version());
    }

    private static Side withChargeType(Side s, UUID type) {
        return new Side(s.tenant(), s.plot(), s.meter(), type, s.month(), s.tariff(), s.version());
    }

    private static Side withMonth(Side s, UUID month) {
        return new Side(s.tenant(), s.plot(), s.meter(), s.chargeType(), month, s.tariff(), s.version());
    }

    private static Side withTariff(Side s, UUID tariff) {
        return new Side(s.tenant(), s.plot(), s.meter(), s.chargeType(), s.month(), tariff, s.version());
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
