package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The land tax charged per plot for a year ({@link LedgerReadModel#chargedInYear}, feature 013 §3.7) on a fixed ledger
 * of a cooperative of its own (service period, billing month):
 * <pre>
 * P1  land tax  D24 17.50 (12.2024, booked 12.2024)
 *               J25 17.50 (01.2025, 01.2025) adjusted +2.50 in 03.2025; F25 17.50 (02.2025, 02.2025)
 *               D25 17.50 (12.2025, booked late in 01.2026); J26 17.50 (01.2026, 01.2026)
 *     membership 25.00 (03.2025)
 * P2  land tax  opening balance 100.00 (dated 01.01.2025); M25 17.50 (03.2025) reversed -17.50
 * P3  nothing
 * </pre>
 * 2025: P1 17.50 + 2.50 + 17.50 + 17.50 = 55.00 (D24 and J26 belong to other years, membership is another type);
 * P2 17.50 - 17.50 = 0.00 (the opening balance is not a charge of the year); P3 0.00. 2024: P1 17.50.
 * <p>
 * Per charge type over a range of days ({@link LedgerReadModel#chargedByType}), every plot: 01.01-31.03.2025 land
 * tax J25 17.50 + 2.50, F25 17.50, M25 17.50 - 17.50 = 37.50 (not the opening balance of 100.00), membership 25.00;
 * February alone land tax 17.50 and no membership.
 */
class LedgerChargedInYearTests extends FreshCooperativeSupport {

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID otherCharge;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
        UUID landTax = typeId("LAND_TAX");

        charge(p1, landTax, "REGULAR", "17.50", "2024-12-01", "2024-12-31", "2024-12");
        UUID j25 = charge(p1, landTax, "REGULAR", "17.50", "2025-01-01", "2025-01-31", "2025-01");
        charge(p1, landTax, "REGULAR", "17.50", "2025-02-01", "2025-02-28", "2025-02");
        charge(p1, landTax, "REGULAR", "17.50", "2025-12-01", "2025-12-31", "2026-01");
        charge(p1, landTax, "REGULAR", "17.50", "2026-01-01", "2026-01-31", "2026-01");
        charge(p1, typeId("MEMBERSHIP"), "REGULAR", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        UUID recalculation = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено площу', 2.50, 1, 1, 'test')", recalculation, tenant);
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', 2.50, "
                + "service_from, service_to, accrued_on, ?, id, ?, 'Виправлено площу' from charge where id = ?",
                monthRow("2025-03"), "ADJUSTMENT:" + recalculation + ":" + j25, recalculation, j25);

        charge(p2, landTax, "OPENING", "100.00", "2025-01-01", "2025-01-01", "2025-01");
        UUID m25 = charge(p2, landTax, "REGULAR", "17.50", "2025-03-01", "2025-03-31", "2025-03");
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", m25);
    }

    @AfterEach
    void removeOtherCooperativeCharge() {
        if (otherCharge != null) {
            UUID charge = otherCharge;
            asLedgerOwner(sql -> sql.update("delete from charge where id = ?", charge));
            otherCharge = null;
        }
    }

    @Test
    void theYearIsTheFirstServiceDayAndAdjustmentsAndReversalsCountWithTheirCharge() {
        Map<UUID, Money> charged = charged(tenant, "LAND_TAX", 2025, List.of(p1, p2, p3));

        assertThat(charged).containsExactly(Map.entry(p1, Money.parse("55.00")), Map.entry(p2, Money.ZERO),
                Map.entry(p3, Money.ZERO));
    }

    @Test
    void anotherYearCountsItsOwnCharges() {
        assertThat(charged(tenant, "LAND_TAX", 2024, List.of(p1, p2))).containsExactly(
                Map.entry(p1, Money.parse("17.50")), Map.entry(p2, Money.ZERO));
        assertThat(charged(tenant, "LAND_TAX", 2026, List.of(p1))).containsExactly(
                Map.entry(p1, Money.parse("17.50")));
    }

    @Test
    void anotherChargeTypeOrAnUnknownCodeCountsOnlyItsOwn() {
        assertThat(charged(tenant, "MEMBERSHIP", 2025, List.of(p1))).containsExactly(
                Map.entry(p1, Money.parse("25.00")));
        assertThat(charged(tenant, "NO_SUCH_TYPE", 2025, List.of(p1, p2))).containsExactly(
                Map.entry(p1, Money.ZERO), Map.entry(p2, Money.ZERO));
    }

    @Test
    void repeatedIdsAreAskedOnceAndNoIdsGiveNothing() {
        assertThat(charged(tenant, "LAND_TAX", 2025, List.of(p2, p1, p2)).keySet()).containsExactly(p2, p1);
        assertThat(charged(tenant, "LAND_TAX", 2025, List.of())).isEmpty();
    }

    @Test
    void theChargesOfAnotherCooperativeAreNeverCounted() {
        Side other = side(OTHER_TENANT);
        UUID otherLandTax = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? "
                + "and code = 'LAND_TAX'", UUID.class, OTHER_TENANT);
        otherCharge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', 99.00, date '2025-06-01', date '2025-06-30', date '2025-06-01', ?)", otherCharge,
                OTHER_TENANT, other.plot(), otherLandTax, other.month(), "TEST:" + otherCharge);

        assertThat(charged(tenant, "LAND_TAX", 2025, List.of(p1, other.plot()))).containsExactly(
                Map.entry(p1, Money.parse("55.00")), Map.entry(other.plot(), Money.ZERO));
        assertThat(charged(OTHER_TENANT, "LAND_TAX", 2025, List.of(p1, other.plot()))).containsExactly(
                Map.entry(p1, Money.ZERO), Map.entry(other.plot(), Money.parse("99.00")));
    }

    @Test
    void perChargeTypeTheRangeOfServiceDaysCountsWithoutOpeningBalances() {
        UUID landTax = typeId("LAND_TAX");
        UUID membership = typeId("MEMBERSHIP");

        assertThat(byType(tenant, "2025-01-01", "2025-03-31")).containsOnly(
                Map.entry(landTax, Money.parse("37.50")), Map.entry(membership, Money.parse("25.00")));
        assertThat(byType(tenant, "2025-02-01", "2025-02-28")).containsOnly(
                Map.entry(landTax, Money.parse("17.50")));
        assertThat(byType(tenant, "2027-01-01", "2027-12-31")).isEmpty();
    }

    @Test
    void perChargeTypeNeverCountsAnotherCooperativeAndRefusesARangeBackwards() {
        Side other = side(OTHER_TENANT);
        UUID otherLandTax = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? "
                + "and code = 'LAND_TAX'", UUID.class, OTHER_TENANT);
        Money before = byType(OTHER_TENANT, "2025-06-01", "2025-06-30").getOrDefault(otherLandTax, Money.ZERO);
        otherCharge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', 99.00, date '2025-06-01', date '2025-06-30', date '2025-06-01', ?)", otherCharge,
                OTHER_TENANT, other.plot(), otherLandTax, other.month(), "TEST:" + otherCharge);

        assertThat(byType(tenant, "2025-06-01", "2025-06-30")).isEmpty();
        assertThat(byType(OTHER_TENANT, "2025-06-01", "2025-06-30")).containsEntry(otherLandTax,
                before.plus(Money.parse("99.00")));
        assertThatThrownBy(() -> byType(tenant, "2025-02-01", "2025-01-31"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.chargedByType(
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.chargedInYear("LAND_TAX", 2025,
                List.of(p1)))).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private Map<UUID, Money> charged(UUID cooperative, String code, int year, List<UUID> plots) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.chargedInYear(code, year,
                plots)));
    }

    private Map<UUID, Money> byType(UUID cooperative, String from, String to) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.chargedByType(
                LocalDate.parse(from), LocalDate.parse(to))));
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    private UUID charge(UUID plot, UUID type, String kind, String amount, String from, String to, String bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?::date, "
                + "?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn), kind,
                kind.equals("OPENING") ? "OPENING" : "TARIFF", new BigDecimal(amount), from, to, from, "TEST:" + id);
        return id;
    }
}
