package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The ledger's read facade for the reports ({@link LedgerReadModel#monthTotals}, feature 013 §3.1) on a fixed ledger
 * of a cooperative of its own, with worked numbers:
 * <pre>
 * January 2025 (closed)   P1 membership 30.00, P2 membership 30.00, P1 opening balance 1500.00 (membership, dated
 *                         01.01.2025), P2 electricity 52.50 for 16.12.2024-15.01.2025
 * February 2025 (closed)  P1 membership 30.00, P2 membership 30.00 and its reversal -30.00
 * March 2025 (open)       P1 membership 25.00, P2 membership 25.00, adjustments of P1's January and February
 *                         membership -5.00 each (booked in March, service periods of January and February)
 * </pre>
 * By billing month: January membership 60.00 + 1500.00 = 1560.00 and electricity 52.50, February 60.00 - 30.00 = 30.00,
 * March 50.00 - 10.00 = 40.00, together 1682.50. By service month the adjustments move to January and February
 * (1555.00 and 25.00), March is 50.00 and the electricity goes to December 2024. The billing months screen must show
 * the same totals for every month, cent for cent.
 */
class LedgerReadModelTests extends FreshCooperativeSupport {

    private static final String MONTHS = "/api/v1/billing-months";
    private static final YearMonth DEC = YearMonth.of(2024, 12);
    private static final YearMonth JAN = YearMonth.of(2025, 1);
    private static final YearMonth MAR = YearMonth.of(2025, 3);

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID membership;
    private UUID electricity;
    private Map<UUID, String> codes;

    private UUID otherCharge;
    private UUID otherMonth;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");
        codes = Map.of(membership, "MEMBERSHIP", electricity, "ELECTRICITY");

        UUID janP1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(p1, membership, "OPENING", "OPENING", "1500.00", "2025-01-01", "2025-01-01", "2025-01");
        charge(p2, electricity, "REGULAR", "CONSUMPTION", "52.50", "2024-12-16", "2025-01-15", "2025-01");

        UUID febP1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        UUID febP2 = charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        reversal(febP2);

        charge(p1, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        charge(p2, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        UUID recalculation = recalculation();
        adjustment(janP1, recalculation, "-5.00", "2025-03");
        adjustment(febP1, recalculation, "-5.00", "2025-03");

        closeMonth("2025-01");
        closeMonth("2025-02");
    }

    @AfterEach
    void removeOtherCooperativeCharge() {
        if (otherCharge != null) {
            UUID charge = otherCharge;
            UUID month = otherMonth;
            asLedgerOwner(sql -> {
                sql.update("delete from charge where id = ?", charge);
                if (month != null) {
                    sql.update("delete from billing_month where id = ?", month);
                }
            });
            otherCharge = null;
            otherMonth = null;
        }
    }

    // ---- by billing month

    @Test
    void byBillingMonthEachMonthAndTypeIsSplitByKindAndEffectiveIsTheSignedSum() {
        List<MonthTypeTotals> rows = totals(tenant, JAN, MAR, Set.of(), null, MonthGrouping.BILLING_MONTH);

        assertThat(rows).extracting(this::line).containsExactlyInAnyOrder(
                "2025-01 CLOSED MEMBERSHIP plots=2 regular=60.00 opening=1500.00 reversals=0.00 adjustments=0.00 "
                        + "effective=1560.00",
                "2025-01 CLOSED ELECTRICITY plots=1 regular=52.50 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=52.50",
                "2025-02 CLOSED MEMBERSHIP plots=2 regular=60.00 opening=0.00 reversals=-30.00 adjustments=0.00 "
                        + "effective=30.00",
                "2025-03 OPEN MEMBERSHIP plots=2 regular=50.00 opening=0.00 reversals=0.00 adjustments=-10.00 "
                        + "effective=40.00");
        assertThat(rows).extracting(MonthTypeTotals::month).isSorted();
        assertThat(sum(rows)).isEqualTo(Money.parse("1682.50"));
    }

    @Test
    void theMonthsOutsideTheRangeAreLeftOut() {
        List<MonthTypeTotals> rows = totals(tenant, YearMonth.of(2025, 2), YearMonth.of(2025, 2), Set.of(), null,
                MonthGrouping.BILLING_MONTH);

        assertThat(rows).extracting(this::line).containsExactly(
                "2025-02 CLOSED MEMBERSHIP plots=2 regular=60.00 opening=0.00 reversals=-30.00 adjustments=0.00 "
                        + "effective=30.00");
    }

    // ---- by service month

    @Test
    void byServiceMonthAnAdjustmentCountsInTheMonthOfTheChargeItCorrects() {
        List<MonthTypeTotals> rows = totals(tenant, DEC, MAR, Set.of(), null, MonthGrouping.SERVICE_MONTH);

        assertThat(rows).extracting(this::line).containsExactlyInAnyOrder(
                "2024-12 - ELECTRICITY plots=1 regular=52.50 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=52.50",
                "2025-01 - MEMBERSHIP plots=2 regular=60.00 opening=1500.00 reversals=0.00 adjustments=-5.00 "
                        + "effective=1555.00",
                "2025-02 - MEMBERSHIP plots=2 regular=60.00 opening=0.00 reversals=-30.00 adjustments=-5.00 "
                        + "effective=25.00",
                "2025-03 - MEMBERSHIP plots=2 regular=50.00 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=50.00");
        // every row's service month is in the range, so the grand total is the billing months' one
        assertThat(sum(rows)).isEqualTo(Money.parse("1682.50"));
    }

    @Test
    void byServiceMonthARowWhoseServiceStartsBeforeTheRangeIsLeftOut() {
        List<MonthTypeTotals> rows = totals(tenant, JAN, MAR, Set.of(), null, MonthGrouping.SERVICE_MONTH);

        assertThat(rows).extracting(MonthTypeTotals::chargeTypeId).containsOnly(membership);
        assertThat(sum(rows)).isEqualTo(Money.parse("1630.00"));
    }

    // ---- reconciliation with the billing months screen

    @Test
    void everyMonthOfTheBillingMonthsScreenHasTheSameTotalsCentForCent() throws Exception {
        JsonNode screen = body(send(chairman, get(MONTHS), null).andExpect(status().isOk()));
        YearMonth first = YearMonth.parse(screen.get(screen.size() - 1).get("month").asString());
        YearMonth last = YearMonth.parse(screen.get(0).get("month").asString());
        assertThat(first).isEqualTo(JAN);

        List<MonthTypeTotals> rows = totals(tenant, first, last, Set.of(), null, MonthGrouping.BILLING_MONTH);

        Map<YearMonth, List<MonthTypeTotals>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(MonthTypeTotals::month));
        assertThat(screen).hasSize((int) first.until(last, ChronoUnit.MONTHS) + 1);
        for (JsonNode month : screen) {
            YearMonth ym = YearMonth.parse(month.get("month").asString());
            List<MonthTypeTotals> ofMonth = byMonth.getOrDefault(ym, List.of());
            assertThat(sum(ofMonth).toString()).as("total of %s", ym).isEqualTo(month.get("total").asString());
            Map<String, String> screenTypes = new HashMap<>();
            month.get("totals").forEach(t -> screenTypes.put(t.get("chargeTypeId").asString(),
                    t.get("amount").asString()));
            Map<String, String> reportTypes = ofMonth.stream().collect(Collectors.toMap(
                    r -> r.chargeTypeId().toString(), r -> r.effective().toString()));
            assertThat(reportTypes).as("types of %s", ym).isEqualTo(screenTypes);
            ofMonth.forEach(r -> assertThat(r.status().name()).as("status of %s", ym)
                    .isEqualTo(month.get("status").asString()));
        }
        assertThat(byMonth.keySet()).containsExactlyInAnyOrder(JAN, YearMonth.of(2025, 2), MAR);
    }

    // ---- filters

    @Test
    void thePlotFilterCountsAndSumsOnlyThosePlots() {
        List<MonthTypeTotals> rows = totals(tenant, JAN, MAR, Set.of(), Set.of(p2), MonthGrouping.BILLING_MONTH);

        assertThat(rows).extracting(this::line).containsExactlyInAnyOrder(
                "2025-01 CLOSED MEMBERSHIP plots=1 regular=30.00 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=30.00",
                "2025-01 CLOSED ELECTRICITY plots=1 regular=52.50 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=52.50",
                "2025-02 CLOSED MEMBERSHIP plots=1 regular=30.00 opening=0.00 reversals=-30.00 adjustments=0.00 "
                        + "effective=0.00",
                "2025-03 OPEN MEMBERSHIP plots=1 regular=25.00 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=25.00");
        assertThat(totals(tenant, JAN, MAR, Set.of(), Set.of(), MonthGrouping.BILLING_MONTH)).isEmpty();
    }

    @Test
    void theChargeTypeFilterCountsOnlyThoseTypes() {
        List<MonthTypeTotals> rows = totals(tenant, JAN, MAR, Set.of(electricity), null,
                MonthGrouping.BILLING_MONTH);

        assertThat(rows).extracting(this::line).containsExactly(
                "2025-01 CLOSED ELECTRICITY plots=1 regular=52.50 opening=0.00 reversals=0.00 adjustments=0.00 "
                        + "effective=52.50");
    }

    // ---- isolation and contract

    @Test
    void theChargesOfAnotherCooperativeAreNeverCounted() {
        Side other = side(OTHER_TENANT);
        otherMonth = ownerJdbc().queryForList("insert into billing_month (tenant_id, month) "
                + "values (?, date '2025-01-01') on conflict (tenant_id, month) do nothing returning id", UUID.class,
                OTHER_TENANT).stream().findFirst().orElse(null);
        UUID month = ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? "
                + "and month = date '2025-01-01'", UUID.class, OTHER_TENANT);
        otherCharge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', 999.00, date '2025-01-01', date '2025-01-31', date '2025-01-01', ?)", otherCharge,
                OTHER_TENANT, other.plot(), other.chargeType(), month, "TEST:" + otherCharge);

        List<MonthTypeTotals> ours = totals(tenant, JAN, JAN, Set.of(), null, MonthGrouping.BILLING_MONTH);
        List<MonthTypeTotals> theirs = totals(OTHER_TENANT, JAN, JAN, Set.of(), Set.of(other.plot()),
                MonthGrouping.BILLING_MONTH);

        assertThat(sum(ours)).isEqualTo(Money.parse("1612.50"));
        assertThat(ours).extracting(MonthTypeTotals::chargeTypeId).containsOnly(membership, electricity);
        assertThat(theirs).singleElement().satisfies(row -> {
            assertThat(row.effective()).isEqualTo(Money.parse("999.00"));
            assertThat(row.chargeTypeId()).isEqualTo(other.chargeType());
        });
        // asking the other cooperative's plot from this cooperative finds nothing (RLS, not the filter)
        assertThat(totals(tenant, JAN, JAN, Set.of(), Set.of(other.plot()), MonthGrouping.BILLING_MONTH)).isEmpty();
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.monthTotals(JAN, MAR, Set.of(), null,
                MonthGrouping.BILLING_MONTH))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> totals(tenant, MAR, JAN, Set.of(), null, MonthGrouping.BILLING_MONTH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fixture

    private List<MonthTypeTotals> totals(UUID cooperative, YearMonth from, YearMonth to, Set<UUID> types,
            Set<UUID> plots, MonthGrouping grouping) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.monthTotals(from, to, types,
                plots, grouping)));
    }

    private String line(MonthTypeTotals row) {
        return "%s %s %s plots=%d regular=%s opening=%s reversals=%s adjustments=%s effective=%s".formatted(row.month(),
                row.status() == null ? "-" : row.status(), codes.get(row.chargeTypeId()), row.plotCount(),
                row.regular(), row.opening(), row.reversals(), row.adjustments(), row.effective());
    }

    private static Money sum(List<MonthTypeTotals> rows) {
        return rows.stream().map(MonthTypeTotals::effective).reduce(Money.ZERO, Money::plus);
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** A charge of the kind written as the database owner, accrued on its first service day. */
    private UUID charge(UUID plot, UUID type, String kind, String source, String amount, String from, String to,
            String bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?::date, "
                + "?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn), kind, source,
                new BigDecimal(amount), from, to, from, "TEST:" + id);
        return id;
    }

    /** A reversal by hand in the original's month: the opposite amount, the same service period. */
    private void reversal(UUID original) {
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", original);
    }

    private UUID recalculation() {
        UUID id = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', -10.00, 2, 1, 'test-digest')", id, tenant);
        return id;
    }

    /** An adjustment of the charge booked in {@code bookedIn}, with the original's service period and debt date. */
    private void adjustment(UUID original, UUID recalculation, String amount, String bookedIn) {
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', ?, "
                + "service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку' from charge where id = ?",
                monthRow(bookedIn), new BigDecimal(amount), "ADJUSTMENT:" + recalculation + ":" + original,
                recalculation, original);
    }
}
