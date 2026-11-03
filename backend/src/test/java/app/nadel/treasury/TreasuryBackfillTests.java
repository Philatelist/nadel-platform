package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The backfill of V32: every payment row that exists when the feature is installed gets exactly one journal movement.
 * The migration has already run when a test starts (on a database without payments), so the tests create the payment
 * rows and run the backfill statements of the migration file (between its BACKFILL markers) again.
 */
class TreasuryBackfillTests extends TreasuryTestSupport {

    private UUID plot;
    private UUID january;
    private UUID february;
    private UUID memberA;
    private UUID cashPayment;
    private UUID bankPayment;
    private UUID cashReversal;
    private UUID bankReversal;
    private UUID otherTenantPayment;

    @BeforeEach
    void createPayments() {
        memberA = member(tenantA);
        plot = plot(tenantA);
        january = billingMonth(tenantA, "2033-01-01");
        february = billingMonth(tenantA, "2033-02-01");
        cashPayment = payment(tenantA, plot, january, "CASH", "120.50", "2033-01-05", "2033-01-05T10:00:00Z", null, 1);
        bankPayment = payment(tenantA, plot, january, "BANK", "80.00", "2033-01-20", "2033-01-20T09:00:00Z", null, null);
        // made at 00:30 on 1 March in Kyiv (22:30 UTC on 28 February), recorded in the February month
        cashReversal = payment(tenantA, plot, february, "CASH", "-120.50", "2033-01-05", "2033-02-28T22:30:00Z",
                cashPayment, null);
        // made on 25 January, recorded in January
        bankReversal = payment(tenantA, plot, january, "BANK", "-80.00", "2033-01-20", "2033-01-25T12:00:00Z",
                bankPayment, null);
        UUID plotB = plot(tenantB);
        otherTenantPayment = payment(tenantB, plotB, billingMonth(tenantB, "2033-01-01"), "BANK", "10.00", "2033-01-02",
                "2033-01-02T08:00:00Z", null, null);
    }

    @Test
    void everyPaymentRowGetsExactlyOneMovement() {
        backfill();

        assertThat(count("select count(*) from account_movement where payment_row_id is not null and tenant_id in (?, ?)",
                tenantA, tenantB)).isEqualTo(5);
        for (UUID row : List.of(cashPayment, bankPayment, cashReversal, bankReversal, otherTenantPayment)) {
            assertThat(count("select count(*) from account_movement where payment_row_id = ?", row)).as(row.toString())
                    .isEqualTo(1);
        }
    }

    @Test
    void aPaymentIsDatedOnItsReceiptDayInItsBillingMonthOnTheAccountOfItsSource() {
        backfill();

        Map<String, Object> cash = movementOf(cashPayment);
        assertThat(cash.get("kind")).isEqualTo("MEMBER_PAYMENT");
        assertThat((BigDecimal) cash.get("amount")).isEqualByComparingTo("120.50");
        assertThat(cash.get("moved_on")).isEqualTo(Date.valueOf("2033-01-05"));
        assertThat(cash.get("booking_month")).isEqualTo(Date.valueOf("2033-01-01"));
        assertThat(cash.get("account_id")).isEqualTo(account(tenantA, "CASH"));
        assertThat(cash.get("source_type")).isEqualTo("PAYMENT");
        assertThat(cash.get("source_id")).isEqualTo(cashPayment);
        assertThat(cash.get("reverses_movement_id")).isNull();
        assertThat(cash.get("reason")).isNull();

        Map<String, Object> bank = movementOf(bankPayment);
        assertThat(bank.get("account_id")).isEqualTo(account(tenantA, "BANK"));
        assertThat(movementOf(otherTenantPayment).get("account_id")).isEqualTo(account(tenantB, "BANK"));
    }

    @Test
    void aReversalIsDatedOnItsDayInKyivInTheLaterOfItsMonthAndThatDaysMonth() {
        backfill();

        // 22:30 UTC on 28 February is 1 March in Kyiv: the day is 1 March and the month moves up from February
        Map<String, Object> cash = movementOf(cashReversal);
        assertThat((BigDecimal) cash.get("amount")).isEqualByComparingTo("-120.50");
        assertThat(cash.get("moved_on")).isEqualTo(Date.valueOf("2033-03-01"));
        assertThat(cash.get("booking_month")).isEqualTo(Date.valueOf("2033-03-01"));
        assertThat(cash.get("account_id")).isEqualTo(account(tenantA, "CASH"));
        assertThat(cash.get("reason")).isEqualTo("Entered twice");

        // made on 25 January in the January month: nothing moves
        Map<String, Object> bank = movementOf(bankReversal);
        assertThat((BigDecimal) bank.get("amount")).isEqualByComparingTo("-80.00");
        assertThat(bank.get("moved_on")).isEqualTo(Date.valueOf("2033-01-25"));
        assertThat(bank.get("booking_month")).isEqualTo(Date.valueOf("2033-01-01"));
    }

    @Test
    void aReversalMovementPointsAtTheMovementOfItsPayment() {
        backfill();

        assertThat(movementOf(cashReversal).get("reverses_movement_id")).isEqualTo(movementOf(cashPayment).get("id"));
        assertThat(movementOf(bankReversal).get("reverses_movement_id")).isEqualTo(movementOf(bankPayment).get("id"));
    }

    @Test
    void aSecondRunChangesNothingAndAddsOnlyTheNewRows() {
        backfill();
        List<UUID> before = ownerJdbc().queryForList(
                "select id from account_movement where tenant_id in (?, ?) order by id", UUID.class, tenantA, tenantB);

        backfill();

        assertThat(ownerJdbc().queryForList("select id from account_movement where tenant_id in (?, ?) order by id",
                UUID.class, tenantA, tenantB)).isEqualTo(before);

        UUID late = payment(tenantA, plot, february, "CASH", "5.00", "2033-02-02", "2033-02-02T08:00:00Z", null, 2);
        backfill();

        assertThat(count("select count(*) from account_movement where tenant_id = ?", tenantA)).isEqualTo(5);
        assertThat(count("select count(*) from account_movement where payment_row_id = ?", late)).isEqualTo(1);
    }

    @Test
    void theBalanceOfAnAccountIsTheSumOfItsPaymentRows() {
        backfill();

        assertThat(ownerJdbc().queryForObject("select sum(amount) from account_movement where account_id = ?",
                BigDecimal.class, account(tenantA, "CASH"))).isEqualByComparingTo("0.00");
        assertThat(ownerJdbc().queryForObject("select sum(amount) from account_movement where account_id = ?",
                BigDecimal.class, account(tenantA, "BANK"))).isEqualByComparingTo("0.00");
        assertThat(ownerJdbc().queryForObject("select sum(amount) from account_movement where tenant_id = ?",
                BigDecimal.class, tenantB)).isEqualByComparingTo("10.00");
    }

    // ---- helpers

    private Map<String, Object> movementOf(UUID paymentRow) {
        return ownerJdbc().queryForMap("select * from account_movement where payment_row_id = ?", paymentRow);
    }

    private int count(String sql, Object... args) {
        return ownerJdbc().queryForObject(sql, Integer.class, args);
    }

    private UUID payment(UUID tenant, UUID plotId, UUID month, String source, String amount, String receivedOn,
            String createdAt, UUID reverses, Integer receipt) {
        UUID id = UUID.randomUUID();
        boolean reversal = reverses != null;
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, receipt_number, reverses_payment_id, reason, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?::timestamptz)", id, tenant, plotId,
                reversal ? "PAYMENT_REVERSAL" : "PAYMENT", source, new BigDecimal(amount), receivedOn, month,
                UUID.randomUUID(), receipt, reverses, reversal ? "Entered twice" : null, createdAt);
        return id;
    }

    /** Runs the backfill statements of the migration as the owner. */
    private static void backfill() {
        ownerJdbc().execute(backfillSql());
    }

    private static String backfillSql() {
        try (InputStream in = TreasuryBackfillTests.class.getResourceAsStream("/db/migration/V32__treasury.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int from = sql.indexOf("-- BACKFILL BEGIN");
            int to = sql.indexOf("-- BACKFILL END");
            assertThat(from).isPositive();
            assertThat(to).isGreaterThan(from);
            return sql.substring(from, to);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
