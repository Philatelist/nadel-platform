package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booked runs of a range ({@link LedgerReadModel#lossesRunsInRange}, {@link LedgerReadModel#commonConsumerRunsInRange})
 * and their charges in {@link LedgerReadModel#electricityCharged}, on runs booked through the API in a cooperative of its
 * own with proportional losses, September 2026 (values on 31.08 and 30.09):
 * <pre>
 * Квартал А     5 000 -> 6 100 = 1 100; children 1 050: P1 600, P2 400, Освітлення 50 -> losses 50 = 4.55 %:
 *               P1 30 kWh = 129.60, P2 20 kWh = 86.40 (4.3200)
 * Освітлення    50 kWh equally per plot: P1 and P2 25 kWh = 108.00 each (4.3200)
 * </pre>
 * The losses run is booked, reversed as a whole and booked again; the reversed run stays listed as {@code REVERSED}, and
 * its charges come with their reversals in {@code electricityCharged}, so they add up to zero.
 */
class LedgerElectricityRunsTests extends FreshCooperativeSupport {

    private static final String LOSSES_RUNS = "/api/v1/losses-runs";
    private static final String COMMON_RUNS = "/api/v1/common-consumer-runs";
    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-30");
    private static final String BEFORE = "2026-08-31";
    private static final String INSTALLED = "2026-01-01";

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID quarter;
    private UUID lighting;

    @BeforeEach
    void theQuarterWithItsPlotsAndLighting() throws Exception {
        owner().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')", tenant);
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", INSTALLED);
        tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", INSTALLED);
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        quarter = meter("QUARTER", "Квартал А", null, null, 5000, 6100);
        meter("PLOT", null, p1, quarter, 1000, 1600);
        meter("PLOT", null, p2, quarter, 2000, 2400);
        lighting = meter("COMMON_CONSUMER", "Освітлення", null, quarter, 300, 350);
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("split", "EQUAL_PER_PLOT");
        billing.put("servicePlotIds", List.of());
        send(chairman, put("/api/v1/common-consumers/" + lighting + "/billing"), billing).andExpect(status().isOk());
    }

    @Test
    void theNodesOfEveryRunOfTheRangeAreListedAsBookedAReversedRunWithItsStatus() throws Exception {
        UUID first = book(LOSSES_RUNS);
        send(chairman, post(LOSSES_RUNS + "/" + first + "/reverse"), Map.of("reason", "Помилкові покази"))
                .andExpect(status().isOk());
        UUID second = book(LOSSES_RUNS);

        List<LossesRunNodeRow> nodes = inTenantTx(tenant, () -> readModel.lossesRunsInRange(FROM, TO));

        assertThat(nodes).extracting(LossesRunNodeRow::runId).containsExactly(first, second);
        assertThat(nodes).extracting(this::line).containsExactly(
                "REVERSED 2026-09-01..2026-09-30 2026-09 216.00 | QUARTER Квартал А 0 CHARGED 1100 1050 50 4.55 "
                        + "exact 216.00 shared 2 charged 2",
                "BOOKED 2026-09-01..2026-09-30 2026-09 216.00 | QUARTER Квартал А 0 CHARGED 1100 1050 50 4.55 "
                        + "exact 216.00 shared 2 charged 2");
        assertThat(nodes.getFirst().reversedAt()).isNotNull();
        assertThat(nodes.getLast().reversedAt()).isNull();
        assertThat(nodes.getFirst().nodeMeterId()).isEqualTo(quarter);
        assertThat(inTenantTx(tenant, () -> readModel.lossesRunsInRange(LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31")))).isEmpty();
        assertThat(inTenantTx(tenant, () -> readModel.lossesRunsInRange(TO, TO))).hasSize(2);
    }

    @Test
    void theConsumersOfEveryCommonConsumerRunOfTheRangeAreListedAsBooked() throws Exception {
        UUID run = book(COMMON_RUNS);

        List<CommonConsumerRunLineRow> lines = inTenantTx(tenant,
                () -> readModel.commonConsumerRunsInRange(FROM, TO));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.runId()).isEqualTo(run);
            assertThat(line.runStatus()).isEqualTo(RunStatus.BOOKED);
            assertThat(line.runFrom()).isEqualTo(FROM);
            assertThat(line.runTo()).isEqualTo(TO);
            assertThat(line.billingMonth()).hasToString("2026-09");
            assertThat(line.runTotal()).hasToString("216.00");
            assertThat(line.consumerMeterId()).isEqualTo(lighting);
            assertThat(line.consumerName()).isEqualTo("Освітлення");
            assertThat(line.split()).isEqualTo(CommonConsumerSplit.EQUAL_PER_PLOT);
            assertThat(line.outcome()).isEqualTo(CommonConsumerRunLineOutcome.CHARGED);
            assertThat(line.consumption()).isEqualTo(kwh(50));
            assertThat(line.calculated()).isFalse();
            assertThat(line.plotDays()).isEqualTo(60);
            assertThat(line.amount()).hasToString("216.00");
            assertThat(line.plotsShared()).isEqualTo(2);
            assertThat(line.plotsCharged()).isEqualTo(2);
        });
    }

    @Test
    void theChargesOfTheRunsComeWithTheReversalsOfAReversedRun() throws Exception {
        UUID first = book(LOSSES_RUNS);
        send(chairman, post(LOSSES_RUNS + "/" + first + "/reverse"), Map.of("reason", "Помилкові покази"))
                .andExpect(status().isOk());
        UUID second = book(LOSSES_RUNS);
        UUID common = book(COMMON_RUNS);

        List<ChargedRow> rows = inTenantTx(tenant, () -> readModel.electricityCharged(FROM, TO, 100));

        assertThat(rows).extracting(row -> row.source() + " " + row.kind() + " " + run(row.runId(), first, second,
                common) + " " + (row.plotId().equals(p1) ? "P1" : "P2") + " " + row.quantity() + " " + row.amount())
                .containsExactlyInAnyOrder(
                        "LOSSES REGULAR first P1 30 129.60", "LOSSES REVERSAL first P1 -30 -129.60",
                        "LOSSES REGULAR first P2 20 86.40", "LOSSES REVERSAL first P2 -20 -86.40",
                        "LOSSES REGULAR second P1 30 129.60", "LOSSES REGULAR second P2 20 86.40",
                        "COMMON_CONSUMER REGULAR common P1 25 108.00", "COMMON_CONSUMER REGULAR common P2 25 108.00");
        assertThat(rows.stream().filter(row -> first.equals(row.runId())).map(ChargedRow::amount)
                .reduce(Money.ZERO, Money::plus)).hasToString("0.00");
        assertThat(rows.stream().map(ChargedRow::amount).reduce(Money.ZERO, Money::plus)).hasToString("432.00");
        assertThat(rows).filteredOn(row -> row.source() == ChargedSource.LOSSES)
                .allSatisfy(row -> assertThat(row.parts()).allMatch(part -> quarter.equals(part.nodeMeterId())));
        // what the runs booked equals what the ledger lists for them
        Money nodes = inTenantTx(tenant, () -> readModel.lossesRunsInRange(FROM, TO)).stream()
                .filter(node -> node.runStatus() == RunStatus.BOOKED).map(LossesRunNodeRow::amount)
                .reduce(Money.ZERO, Money::plus);
        Money losses = rows.stream().filter(row -> row.source() == ChargedSource.LOSSES).map(ChargedRow::amount)
                .reduce(Money.ZERO, Money::plus);
        assertThat(losses).isEqualTo(nodes);
    }

    @Test
    void anotherCooperativeNeverSeesTheRuns() throws Exception {
        UUID losses = book(LOSSES_RUNS);
        UUID common = book(COMMON_RUNS);

        assertThat(inTenantTx(OTHER_TENANT, () -> readModel.lossesRunsInRange(FROM, TO)))
                .extracting(LossesRunNodeRow::runId).doesNotContain(losses);
        assertThat(inTenantTx(OTHER_TENANT, () -> readModel.commonConsumerRunsInRange(FROM, TO)))
                .extracting(CommonConsumerRunLineRow::runId).doesNotContain(common);
    }

    @Test
    void invalidRangesAreRefusedAndOnlyTheCallersTransactionIsUsed() {
        assertThatThrownBy(() -> inTenantTx(tenant, () -> readModel.lossesRunsInRange(TO, FROM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTenantTx(tenant, () -> readModel.commonConsumerRunsInRange(TO, FROM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.lossesRunsInRange(FROM, TO)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.commonConsumerRunsInRange(FROM, TO)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private <T> T inTenantTx(UUID cooperative, Supplier<T> work) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> work.get()));
    }

    /** Books a run of September as the chairman (no preview digest needed) and returns its id. */
    private UUID book(String runs) throws Exception {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", FROM.toString());
        range.put("to", TO.toString());
        return UUID.fromString(body(send(chairman, post(runs + "?preview=false"), range).andExpect(status().isOk()))
                .get("runId").asString());
    }

    private String line(LossesRunNodeRow node) {
        return node.runStatus() + " " + node.runFrom() + ".." + node.runTo() + " " + node.billingMonth() + " "
                + node.runTotal() + " | " + node.nodeKind() + " " + node.nodeName() + " " + node.position() + " "
                + node.outcome() + " " + node.consumption() + " " + node.children() + " " + node.losses() + " "
                + node.percent().toPlainString() + " " + (node.calculated() ? "calculated" : "exact") + " "
                + node.amount() + " shared " + node.plotsShared() + " charged " + node.plotsCharged();
    }

    private static String run(@Nullable UUID runId, UUID first, UUID second, UUID common) {
        if (first.equals(runId)) {
            return "first";
        }
        if (second.equals(runId)) {
            return "second";
        }
        return common.equals(runId) ? "common" : String.valueOf(runId);
    }

    private static Quantity kwh(long value) {
        return Quantity.ofFraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    /** A meter read on 31.08 and 30.09, placed under {@code parent} since its installation. */
    private UUID meter(String kind, @Nullable String name, @Nullable UUID plot, @Nullable UUID parent, int before,
            int after) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?, ?::date, 0)", id, tenant, kind,
                "ER-" + id.toString().substring(0, 8), name, plot, INSTALLED);
        if (parent != null) {
            owner().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, id, parent, INSTALLED);
        }
        for (String[] reading : List.of(new String[] {BEFORE, Integer.toString(before)},
                new String[] {TO.toString(), Integer.toString(after)})) {
            owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                    + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, id, reading[0],
                    new BigDecimal(reading[1]));
        }
        return id;
    }
}
