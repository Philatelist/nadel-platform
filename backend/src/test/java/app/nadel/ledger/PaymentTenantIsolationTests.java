package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The payment tables (payment, allocation, receipt_counter): cooperatives are isolated (RLS, no cooperative sees
 * nothing, nothing is written for another one), links never cross cooperatives or plots (composite foreign keys), and
 * every cooperative has its own receipt counter.
 */
class PaymentTenantIsolationTests extends PaymentTestSupport {

    private static final List<String> TABLES = List.of("payment", "allocation", "receipt_counter");
    private static final String PAYMENT_INSERT = "insert into payment (id, tenant_id, plot_id, kind, source, amount, "
            + "received_on, billing_month_id, group_id, payer_member_id, created_by, reverses_payment_id, reason) "
            + "values (?, ?, ?, ?, 'BANK', ?::numeric, date '2031-01-15', ?, ?, ?, ?, ?, ?)";
    private static final String ALLOCATION_INSERT = "insert into allocation (tenant_id, payment_id, charge_id, plot_id, "
            + "amount, origin, created_by) values (?, ?, ?, ?, 1.00, 'ENTRY', ?)";

    private Side demo;
    private Side other;
    private UUID demoCharge;
    private UUID otherCharge;
    private UUID demoPayment;
    private UUID otherPayment;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        demoCharge = insertCharge(demo, "PAYISO:demo");
        otherCharge = insertCharge(other, "PAYISO:other");
        demoPayment = bank(demo, "10.00");
        otherPayment = bank(other, "10.00");
        allocate(demo, demoPayment, demoCharge, "4.00");
        allocate(other, otherPayment, otherCharge, "4.00");
        counter(DEMO_TENANT, 7);
        counter(OTHER_TENANT, 3);
    }

    // ---- row level security

    @Test
    void everyTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation' "
                            + "and qual like '%current_tenant_id%' and with_check like '%current_tenant_id%'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRows() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            UUID own = tenant.equals(DEMO_TENANT) ? demoPayment : otherPayment;
            assertThat(count(tenant, "payment", "where id in ('" + demoPayment + "', '" + otherPayment + "')"))
                    .isEqualTo(1);
            assertThat(count(tenant, "payment", "where id = '" + own + "'")).isEqualTo(1);
            assertThat(count(tenant, "allocation", "where payment_id in ('" + demoPayment + "', '" + otherPayment
                    + "')")).isEqualTo(1);
            assertThat(count(tenant, "receipt_counter", "")).isEqualTo(1);
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
        assertRowSecurity(sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), OTHER_TENANT, other.plot(), "PAYMENT",
                "5.00", other.month(), UUID.randomUUID(), null, null, null, null));
        assertRowSecurity(sql -> sql.update(ALLOCATION_INSERT, OTHER_TENANT, otherPayment, otherCharge, other.plot(),
                null));
        assertRowSecurity(sql -> sql.update("insert into receipt_counter (tenant_id, last_number) values (?, 0)",
                UUID.randomUUID()));
        assertThat(ownerJdbc().queryForObject("select count(*) from payment where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isEqualTo(1);
    }

    @Test
    void cannotUpdateOrDeleteTheCounterOfAnotherCooperative() {
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update receipt_counter set last_number = last_number + 1 where tenant_id = ?", OTHER_TENANT));

        assertThat(updated).isZero();
        assertThat(lastNumber(OTHER_TENANT)).isEqualTo(3L);
        // the cooperative that owns the row can move it forward, and only by one
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(lastNumber(DEMO_TENANT)).isEqualTo(8L);
        assertThat(lastNumber(OTHER_TENANT)).isEqualTo(3L);
    }

    @Test
    void cannotHandTheCounterToAnotherCooperative() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update receipt_counter set tenant_id = ? where tenant_id = ?", OTHER_TENANT, DEMO_TENANT)))
                .isInstanceOf(DataAccessException.class);
        assertThat(lastNumber(DEMO_TENANT)).isEqualTo(7L);
        assertThat(ownerJdbc().queryForObject("select count(*) from receipt_counter where tenant_id = ?",
                Integer.class, OTHER_TENANT)).isEqualTo(1);
    }

    // ---- composite foreign keys: payment

    @Test
    void aPaymentCannotLinkRowsOfAnotherCooperative() {
        UUID otherMember = newMember(OTHER_TENANT, "payiso-other@ledger.local");

        assertForeignKey("payment_plot_fk", sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                other.plot(), "PAYMENT", "5.00", demo.month(), UUID.randomUUID(), null, null, null, null));
        assertForeignKey("payment_billing_month_fk", sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), "PAYMENT", "5.00", other.month(), UUID.randomUUID(), null, null, null, null));
        assertForeignKey("payment_payer_member_fk", sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), "PAYMENT", "5.00", demo.month(), UUID.randomUUID(), otherMember, null, null, null));
        assertForeignKey("payment_created_by_fk", sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), "PAYMENT", "5.00", demo.month(), UUID.randomUUID(), null, otherMember, null, null));
        assertForeignKey("payment_reverses_fk", sql -> sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demo.plot(), "PAYMENT_REVERSAL", "-10.00", demo.month(), UUID.randomUUID(), null, null, otherPayment,
                "Wrong"));
    }

    // ---- composite foreign keys: allocation

    @Test
    void anAllocationCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("allocation_payment_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT, otherPayment,
                demoCharge, demo.plot(), null));
        assertForeignKey("allocation_charge_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT, demoPayment,
                otherCharge, demo.plot(), null));
        assertForeignKey("allocation_payment_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT, demoPayment,
                demoCharge, other.plot(), null));
        UUID otherMember = newMember(OTHER_TENANT, "payiso-other2@ledger.local");
        assertForeignKey("allocation_created_by_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT, demoPayment,
                demoCharge, demo.plot(), otherMember));
    }

    @Test
    void anAllocationKeepsOnePlotForItsPaymentAndItsCharge() {
        UUID secondPlot = extraPlot(DEMO_TENANT);
        UUID paymentOnSecondPlot = payment(demo, secondPlot, "BANK", "10.00", null, UUID.randomUUID());

        // the payment is on the second plot, the charge on the first: whichever plot the row names, one key breaks
        assertForeignKey("allocation_charge_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT,
                paymentOnSecondPlot, demoCharge, secondPlot, null));
        assertForeignKey("allocation_payment_fk", sql -> sql.update(ALLOCATION_INSERT, DEMO_TENANT,
                paymentOnSecondPlot, demoCharge, demo.plot(), null));
    }

    @Test
    void aReleaseKeepsThePaymentAndTheChargeOfItsAllocation() {
        UUID allocation = ownerJdbc().queryForObject(
                "select id from allocation where payment_id = ?", UUID.class, demoPayment);
        UUID secondCharge = insertCharge(demo, "PAYISO:second");
        UUID secondPayment = bank(demo, "10.00");
        String release = "insert into allocation (tenant_id, payment_id, charge_id, plot_id, amount, "
                + "releases_allocation_id, release_cause) values (?, ?, ?, ?, -1.00, ?, 'REALLOCATED')";

        assertForeignKey("allocation_releases_fk", sql -> sql.update(release, DEMO_TENANT, demoPayment, secondCharge,
                demo.plot(), allocation));
        assertForeignKey("allocation_releases_fk", sql -> sql.update(release, DEMO_TENANT, secondPayment, demoCharge,
                demo.plot(), allocation));
        inTenant(DEMO_TENANT, sql -> sql.update(release, DEMO_TENANT, demoPayment, demoCharge, demo.plot(),
                allocation));
        assertThat(count(DEMO_TENANT, "allocation", "where releases_allocation_id = '" + allocation + "'"))
                .isEqualTo(1);
    }

    // ---- counters

    @Test
    void everyCooperativeHasItsOwnIndependentCounter() {
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(step(OTHER_TENANT)).isEqualTo(1);

        assertThat(lastNumber(DEMO_TENANT)).isEqualTo(9L);
        assertThat(lastNumber(OTHER_TENANT)).isEqualTo(4L);
        assertThat(visibleCounter(DEMO_TENANT)).isEqualTo(9L);
        assertThat(visibleCounter(OTHER_TENANT)).isEqualTo(4L);
    }

    @Test
    void theSameReceiptNumberMayExistInTwoCooperatives() {
        cash(demo, "5.00", 501);
        cash(other, "5.00", 501);

        assertThat(count(DEMO_TENANT, "payment", "where receipt_number = 501")).isEqualTo(1);
        assertThat(count(OTHER_TENANT, "payment", "where receipt_number = 501")).isEqualTo(1);
    }

    // ---- helpers

    private int step(UUID tenant) {
        return inTenant(tenant, sql -> sql.update("update receipt_counter set last_number = last_number + 1"));
    }

    private Long visibleCounter(UUID tenant) {
        return inTenant(tenant, sql -> sql.queryForObject("select last_number from receipt_counter", Long.class));
    }

    private Long lastNumber(UUID tenant) {
        return ownerJdbc().queryForObject("select last_number from receipt_counter where tenant_id = ?", Long.class,
                tenant);
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
}
