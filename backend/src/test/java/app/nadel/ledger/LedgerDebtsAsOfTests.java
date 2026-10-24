package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The debts of plots as of a day ({@link LedgerReadModel#debtsAsOf}, feature 013 §3.2 and §3.3) on a fixed ledger of
 * a cooperative of its own, with worked numbers (debt date in brackets):
 * <pre>
 * P1  membership  J1 30.00 (01.01.2025, January)   adjusted -30.00 in March   effective 0.00
 *                 F1 30.00 (01.02.2025, February)  adjusted  -5.00 in March   effective 25.00
 *                 M1 25.00 (01.03.2025, March)
 *     electricity E1 52.50 (20.01.2025, January)
 * P2  membership  O2 opening 1500.00 (01.12.2024, January)
 *                 J2 30.00 (01.01.2025), F2 30.00 (01.02.2025) and its reversal -30.00, M2 25.00 (01.03.2025)
 * P3  no charges
 * P4  membership  J4 30.00 (01.01.2025) and its reversal -30.00, M4 25.00 (01.03.2025)
 * </pre>
 * As of 31.03.2025: P1 membership 30 - 30 + 30 - 5 + 25 = 50.00, electricity 52.50, total 102.50; J1 is fully adjusted,
 * so its oldest unpaid charge is E1 (20.01.2025, 70 days). P2 membership 1500 + 30 + 30 - 30 + 25 = 1555.00, oldest
 * 01.12.2024 (120 days). P4 membership 25.00: J4 is reversed, so the oldest unpaid charge is M4 (01.03.2025, 30
 * days). As of 31.01.2025 (before F1 and its adjustment): P1 membership 0.00, electricity 52.50, oldest
 * 20.01.2025 (11 days); P2 1530.00. As of 15.01.2025 P1 has membership 0.00 only and nothing unpaid.
 */
class LedgerDebtsAsOfTests extends FreshCooperativeSupport {

    private static final LocalDate END_OF_MARCH = LocalDate.of(2025, 3, 31);

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID p4;
    private UUID membership;
    private UUID electricity;

    private UUID otherCharge;
    private UUID otherMonth;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
        p4 = plot("4", "600");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");

        UUID j1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01");
        UUID f1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02");
        charge(p1, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03");
        charge(p1, electricity, "REGULAR", "CONSUMPTION", "52.50", "2025-01-20", "2025-01");
        UUID recalculation = recalculation();
        adjustment(j1, recalculation, "-30.00", "2025-03");
        adjustment(f1, recalculation, "-5.00", "2025-03");

        charge(p2, membership, "OPENING", "OPENING", "1500.00", "2024-12-01", "2025-01");
        charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01");
        UUID f2 = charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02");
        reversal(f2);
        charge(p2, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03");

        reversal(charge(p4, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01"));
        charge(p4, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03");

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

    @Test
    void everyChargeTypeIsTheSignedSumAndTheOldestUnpaidChargeSkipsFullyAdjustedAndReversedOnes() {
        Map<UUID, PlotDebts> debts = debts(tenant, END_OF_MARCH, Set.of(), List.of(p1, p2, p3, p4));

        assertThat(debts).containsOnlyKeys(p1, p2, p3, p4);
        assertThat(line(debts.get(p1))).isEqualTo(
                "membership=50.00 electricity=52.50 total=102.50 oldest=2025-01-20 days=70");
        assertThat(line(debts.get(p2))).isEqualTo("membership=1555.00 total=1555.00 oldest=2024-12-01 days=120");
        assertThat(debts.get(p3)).isEqualTo(PlotDebts.none(p3));
        assertThat(line(debts.get(p4))).isEqualTo("membership=25.00 total=25.00 oldest=2025-03-01 days=30");
    }

    @Test
    void chargesDatedAfterTheDayAreLeftOutTogetherWithTheirAdjustments() {
        // F1 (01.02.2025) and its adjustment booked in March carry F1's debt date, so 31.01.2025 counts neither
        Map<UUID, PlotDebts> endOfJanuary = debts(tenant, LocalDate.of(2025, 1, 31), Set.of(), List.of(p1, p2));
        // J1 and its adjustment (booked later, dated 01.01.2025) count; E1 (20.01.2025) does not yet
        Map<UUID, PlotDebts> midJanuary = debts(tenant, LocalDate.of(2025, 1, 15), Set.of(), List.of(p1));

        assertThat(line(endOfJanuary.get(p1))).isEqualTo(
                "membership=0.00 electricity=52.50 total=52.50 oldest=2025-01-20 days=11");
        assertThat(line(endOfJanuary.get(p2))).isEqualTo(
                "membership=1530.00 total=1530.00 oldest=2024-12-01 days=61");
        assertThat(line(midJanuary.get(p1))).isEqualTo("membership=0.00 total=0.00 oldest=- days=-");
        assertThat(debts(tenant, LocalDate.of(2024, 11, 30), Set.of(), List.of(p1, p2)).values())
                .containsExactlyInAnyOrder(PlotDebts.none(p1), PlotDebts.none(p2));
    }

    @Test
    void theChargeTypeFilterCountsOnlyThoseTypesAndTheirOldestUnpaidCharge() {
        Map<UUID, PlotDebts> onlyMembership = debts(tenant, END_OF_MARCH, Set.of(membership), List.of(p1, p2));
        Map<UUID, PlotDebts> onlyElectricity = debts(tenant, END_OF_MARCH, Set.of(electricity), List.of(p1, p2));

        assertThat(line(onlyMembership.get(p1))).isEqualTo("membership=50.00 total=50.00 oldest=2025-02-01 days=58");
        assertThat(line(onlyElectricity.get(p1))).isEqualTo(
                "electricity=52.50 total=52.50 oldest=2025-01-20 days=70");
        assertThat(onlyElectricity.get(p2)).isEqualTo(PlotDebts.none(p2));
    }

    @Test
    void asOfTodayTheDebtsAreThoseOfThePlotAccount() throws Exception {
        Map<UUID, PlotDebts> debts = debts(tenant, LocalDate.now(), Set.of(), List.of(p1, p2, p4));

        for (UUID plot : List.of(p1, p2, p4)) {
            JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
            Map<UUID, String> accountDebts = new HashMap<>();
            account.get("debts").forEach(debt -> accountDebts.put(UUID.fromString(debt.get("chargeTypeId").asString()),
                    debt.get("debt").asString()));
            Map<UUID, String> ours = new HashMap<>();
            debts.get(plot).byChargeType().forEach((type, amount) -> ours.put(type, amount.toString()));
            assertThat(ours).as("debts of %s", plot).isEqualTo(accountDebts);
            assertThat(debts.get(plot).total().toString()).isEqualTo(account.get("total").asString());
        }
    }

    @Test
    void asOfTodayTheTotalAndTheOldestUnpaidDateAreThoseOfThePlotStatusCache() {
        // the status refresher rebuilds the cache with DebtCalculator from every charge of the plots
        TenantContext.runAs(tenant, () -> tx.execute(status -> {
            events.publishEvent(new ChargesBooked(tenant, Set.of(p1, p2, p4)));
            return null;
        }));
        await().atMost(Duration.ofSeconds(10)).until(() -> cachedDebt(p1).equals("102.50")
                && cachedDebt(p2).equals("1555.00") && cachedDebt(p4).equals("25.00"));

        Map<UUID, PlotDebts> debts = debts(tenant, LocalDate.now(), Set.of(), List.of(p1, p2, p4));

        for (UUID plot : List.of(p1, p2, p4)) {
            Map<String, Object> cache = owner().queryForMap("select debt::text as debt, oldest_debt_date "
                    + "from plot_status where plot_id = ?", plot);
            assertThat(debts.get(plot).total().toString()).isEqualTo(cache.get("debt"));
            assertThat(debts.get(plot).oldestUnpaid()).isEqualTo(date(cache.get("oldest_debt_date")));
        }
    }

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

        Map<UUID, PlotDebts> fromHere = debts(tenant, END_OF_MARCH, Set.of(), List.of(p1, other.plot()));
        Map<UUID, PlotDebts> fromThere = debts(OTHER_TENANT, END_OF_MARCH, Set.of(), List.of(p1, other.plot()));

        assertThat(fromHere.get(other.plot())).isEqualTo(PlotDebts.none(other.plot()));
        assertThat(fromHere.get(p1).total()).isEqualTo(Money.parse("102.50"));
        assertThat(fromThere.get(p1)).isEqualTo(PlotDebts.none(p1));
        assertThat(fromThere.get(other.plot()).total()).isEqualTo(Money.parse("999.00"));
        assertThat(fromThere.get(other.plot()).oldestUnpaid()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void repeatedIdsAreAskedOnceAndNoIdsGiveNothing() {
        assertThat(debts(tenant, END_OF_MARCH, Set.of(), List.of(p2, p1, p2))).containsOnlyKeys(p2, p1);
        assertThat(debts(tenant, END_OF_MARCH, Set.of(), List.of())).isEmpty();
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.debtsAsOf(END_OF_MARCH, Set.of(),
                List.of(p1)))).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private Map<UUID, PlotDebts> debts(UUID cooperative, LocalDate asOf, Set<UUID> types, List<UUID> plots) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.debtsAsOf(asOf, types, plots)));
    }

    private String line(PlotDebts debts) {
        StringBuilder text = new StringBuilder();
        Money ofMembership = debts.byChargeType().get(membership);
        Money ofElectricity = debts.byChargeType().get(electricity);
        if (ofMembership != null) {
            text.append("membership=").append(ofMembership).append(' ');
        }
        if (ofElectricity != null) {
            text.append("electricity=").append(ofElectricity).append(' ');
        }
        assertThat(debts.byChargeType()).hasSize((ofMembership == null ? 0 : 1) + (ofElectricity == null ? 0 : 1));
        return text.append("total=").append(debts.total())
                .append(" oldest=").append(debts.oldestUnpaid() == null ? "-" : debts.oldestUnpaid())
                .append(" days=").append(debts.daysOverdue() == null ? "-" : debts.daysOverdue()).toString();
    }

    private String cachedDebt(UUID plot) {
        return owner().queryForObject("select debt::text from plot_status where plot_id = ?", String.class, plot);
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** A charge of the kind written as the database owner, with its debt date as its one service day. */
    private UUID charge(UUID plot, UUID type, String kind, String source, String amount, String accruedOn,
            String bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?::date, "
                + "?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn), kind, source,
                new BigDecimal(amount), accruedOn, accruedOn, accruedOn, "TEST:" + id);
        return id;
    }

    /** A reversal by hand in the original's month: the opposite amount, the same service period and debt date. */
    private void reversal(UUID original) {
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", original);
    }

    private UUID recalculation() {
        UUID id = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', -35.00, 2, 1, 'test-digest')", id, tenant);
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
