package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.LossesCalculator.Outcome;
import app.nadel.ledger.internal.LossesCalculator.ValueUse;
import app.nadel.ledger.internal.LossesPlanning.Overlap;
import app.nadel.ledger.internal.LossesPlanning.Plan;
import app.nadel.ledger.internal.LossesPlanning.PlannedCharge;
import app.nadel.ledger.internal.LossesPlanning.PlannedInput;
import app.nadel.ledger.internal.LossesPlanning.PlannedNode;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterNetwork.Placement;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.money.Rate;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Scope;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffBook.VersionRef;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The proportional losses of feature 011 §2.5 and the worked examples of its §3 ("Proportional losses, quarter",
 * "substation", "Negative losses", "Unknown value"): node losses bottom-up, shares in proportion to the plots'
 * consumption, the tree as it was on each day, negative and unknown nodes, calculated values, the values a run reads,
 * and the price of a plot's kWh from every node (once, rounded once).
 */
class LossesCalculatorTests {

    private static final LocalDate FROM = LocalDate.parse("2026-10-01");
    private static final LocalDate TO = LocalDate.parse("2026-10-31");
    private static final LocalDate INSTALLED = LocalDate.parse("2026-01-01");
    private static final LocalDate BEFORE = LocalDate.parse("2026-09-30");

    private static final UUID S = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID WELL = UUID.randomUUID();
    private static final UUID LIGHT = UUID.randomUUID();
    private static final UUID M1 = UUID.randomUUID();
    private static final UUID M2 = UUID.randomUUID();
    private static final UUID M3 = UUID.randomUUID();
    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();

    private static final UUID ELECTRICITY = UUID.randomUUID();

    // ---- §3 rows

    @Test
    void quarterLossesAreSharedByThePlotsConsumption() {
        // quarter A 1 100 kWh; P1 600, P2 400, lighting 0 -> losses 100: P1 60, P2 40
        List<NetworkChain> network = List.of(
                node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400), consumer(LIGHT, "Освітлення", A, 0));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(a.consumption()).isEqualTo(kwh(1100));
        assertThat(a.children()).isEqualTo(kwh(1000));
        assertThat(a.losses()).isEqualTo(kwh(100));
        assertThat(a.calculated()).isFalse();
        assertThat(a.shares()).containsExactly(Map.entry(P1, kwh(60)), Map.entry(P2, kwh(40)));
    }

    @Test
    void quarterLossesArePricedPerPlot() {
        // 60 kWh x 4.3200 = 259.20, 40 kWh x 4.3200 = 172.80
        List<NetworkChain> network = List.of(
                node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400), consumer(LIGHT, "Освітлення", A, 0));

        Plan plan = plan(network, Map.of(), electricity("4.3200"));

        assertThat(charges(plan)).containsExactly(Map.entry(P1, "259.20"), Map.entry(P2, "172.80"));
        assertThat(plan.total()).hasToString("432.00");
        assertThat(plan.nodes().getFirst().amount()).hasToString("432.00");
    }

    @Test
    void substationLossesAreSharedByEveryPlotUnderIt() {
        // substation 2 300; A 1 100 (P1 600, P2 400), B 1 050 (P3 1 000), well 50
        // A: 100 -> P1 60, P2 40; B: 50 -> P3 50; substation: 2 300 - 2 200 = 100 -> P1 30, P2 20, P3 50
        Map<UUID, NodeResult> nodes = byNode(LossesCalculator.calculate(substation(1050, true), FROM, TO));

        assertThat(nodes.get(A).shares()).containsExactly(Map.entry(P1, kwh(60)), Map.entry(P2, kwh(40)));
        assertThat(nodes.get(B).losses()).isEqualTo(kwh(50));
        assertThat(nodes.get(B).shares()).containsExactly(Map.entry(P3, kwh(50)));
        NodeResult s = nodes.get(S);
        assertThat(s.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(s.consumption()).isEqualTo(kwh(2300));
        assertThat(s.children()).isEqualTo(kwh(2200));
        assertThat(s.shares()).containsOnly(Map.entry(P1, kwh(30)), Map.entry(P2, kwh(20)), Map.entry(P3, kwh(50)));
        assertThat(LossesCalculator.plotTotals(nodes.values())).containsOnly(Map.entry(P1, kwh(90)),
                Map.entry(P2, kwh(60)), Map.entry(P3, kwh(100)));
    }

    @Test
    void aPlotIsPricedOnceForItsKwhFromEveryNode() {
        // P1: 60 (A) + 30 (substation) = 90 kWh x 4.3200 = 388.80, one charge with a part per node
        Plan plan = plan(substation(1050, true), Map.of(), electricity("4.3200"));

        assertThat(charges(plan)).containsOnly(Map.entry(P1, "388.80"), Map.entry(P2, "259.20"),
                Map.entry(P3, "432.00"));
        PlannedCharge p1 = plan.charges().stream().filter(c -> c.plotId().equals(P1)).findFirst().orElseThrow();
        assertThat(p1.kwh()).isEqualTo(kwh(90));
        assertThat(p1.parts()).extracting(LossesPlanning.NodePart::nodeKey).containsExactlyInAnyOrder(A, S);
        assertThat(p1.parts()).extracting(part -> part.part().quantity()).containsExactlyInAnyOrder(kwh(60),
                kwh(30));
        Map<String, Object> calculation = p1.calculation(UUID.randomUUID());
        assertThat(calculation).containsEntry("amount", "388.80").doesNotContainKey("nodeMeterId");
    }

    @Test
    void aRateChangeInsideTheRangeSplitsThePlotsKwhByDaysAndRoundsOnce() {
        // 90 kWh over 31 days: 16 days at 4.3200 and 15 at 5.0000 = 90 x (16 x 4.32 + 15 x 5) / 31 = 418.41
        TariffEntry tariff = new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Електроенергія", Scope.ALL_PLOTS,
                Set.of(), Set.of(), List.of(version("2026-01-01", "4.3200"), version("2026-10-17", "5.0000")));

        Plan plan = plan(substation(1050, true), Map.of(), tariff);

        assertThat(charges(plan)).containsEntry(P1, "418.41");
        PlannedCharge p1 = plan.charges().stream().filter(c -> c.plotId().equals(P1)).findFirst().orElseThrow();
        assertThat(p1.parts()).hasSize(4); // two nodes x two versions
    }

    @Test
    void negativeLossesChargeNothing() {
        // quarter 950, children 1 000 -> -50, nothing charged
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 950),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.outcome()).isEqualTo(Outcome.NEGATIVE);
        assertThat(a.losses()).isEqualTo(kwh(-50));
        assertThat(LossesRunViews.kwh(a.losses())).isEqualTo("-50.000");
        assertThat(a.shares()).isEmpty();
        assertThat(a.uses()).isEmpty();
        assertThat(plan(network, Map.of(), electricity("4.3200")).charges()).isEmpty();
    }

    @Test
    void lossesThatAreZeroAtThreeDecimalsAreZeroNeitherNegativeNorShared() {
        // P1 reads 1 000 on 30.09 and 1 092.001 on 31.12 (92 days): the end of 31.10 is 1 000 + 92.001 x 31 / 92
        // = 1 031.000337; quarter 31 -> losses -0.000337 kWh, 0.000 at three decimals
        List<NetworkChain> under = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 31),
                chain(M1, MeterKind.PLOT, P1, null, A, new MeterHistory(INSTALLED, MeterValue.ZERO, null, null,
                        List.of(reading("2026-09-30", "1000"), reading("2026-12-31", "1092.001")))));
        // 1 091.999 instead: 1 030.999663 -> losses +0.000337 kWh, 0.000 as well
        List<NetworkChain> over = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 31),
                chain(M1, MeterKind.PLOT, P1, null, A, new MeterHistory(INSTALLED, MeterValue.ZERO, null, null,
                        List.of(reading("2026-09-30", "1000"), reading("2026-12-31", "1091.999")))));

        for (List<NetworkChain> network : List.of(under, over)) {
            NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

            assertThat(a.outcome()).isEqualTo(Outcome.CHARGED);
            assertThat(a.losses()).isEqualTo(Quantity.ZERO);
            assertThat(LossesRunViews.kwh(a.losses())).isEqualTo("0.000");
            assertThat(a.shares()).isEmpty();
            assertThat(a.noPlotConsumption()).isFalse();
            assertThat(plan(network, Map.of(), electricity("4.3200")).charges()).isEmpty();
        }
        // -0.0006 kWh is -0.001 at three decimals: it stays negative
        Quantity small = Quantity.ofFraction(BigInteger.valueOf(-6), BigInteger.valueOf(10_000));
        assertThat(LossesCalculator.settled(small)).isEqualTo(small);
    }

    @Test
    void anUnknownNodeIsSkippedWithTheNodesAboveItAndTheRestIsCharged() {
        // quarter B has no reading after the range end: B and the substation are skipped, quarter A is charged
        Map<UUID, NodeResult> nodes = byNode(LossesCalculator.calculate(substation(1050, false), FROM, TO));

        NodeResult b = nodes.get(B);
        assertThat(b.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(b.consumption()).isNull();
        assertThat(b.unknown().chainKey()).isEqualTo(B);
        assertThat(b.unknown().lastReadingOn()).isEqualTo(BEFORE);
        NodeResult s = nodes.get(S);
        assertThat(s.outcome()).isEqualTo(Outcome.SKIPPED_ABOVE);
        assertThat(s.below()).isEqualTo(B);
        assertThat(s.shares()).isEmpty();
        assertThat(nodes.get(A).outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(nodes.get(A).shares()).containsExactly(Map.entry(P1, kwh(60)), Map.entry(P2, kwh(40)));

        Plan plan = plan(substation(1050, false), Map.of(), electricity("4.3200"));
        assertThat(charges(plan)).containsOnly(Map.entry(P1, "259.20"), Map.entry(P2, "172.80"));
    }

    @Test
    void anUnknownPlotMeterSkipsItsQuarter() {
        UUID m3 = UUID.randomUUID();
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), chain(m3, MeterKind.PLOT, P2, null, A, history(INSTALLED, 0, BEFORE, 500)));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(a.consumption()).isEqualTo(kwh(1100));
        assertThat(a.children()).isNull();
        assertThat(a.unknown().chainKey()).isEqualTo(m3);
        assertThat(a.unknown().meterId()).isEqualTo(m3);
    }

    // ---- days, values and shares

    @Test
    void aValueBetweenReadingsIsCalculatedAndMarked() {
        // the plot meter reads 0 on 15.09 and 620 on 15.11 (61 days): end of 30.09 and end of 31.10 are interpolated
        UUID meter = UUID.randomUUID();
        MeterHistory history = new MeterHistory(INSTALLED, MeterValue.ZERO, null, null, List.of(
                reading("2026-09-15", "0"), reading("2026-11-15", "610")));
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 400),
                chain(meter, MeterKind.PLOT, P1, null, A, history));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        // 610 x (46 - 15) / 61 = 310 kWh
        assertThat(a.children()).isEqualTo(kwh(310));
        assertThat(a.calculated()).isTrue();
        assertThat(a.uses()).contains(new ValueUse(meter, LocalDate.parse("2026-09-15")),
                new ValueUse(meter, LocalDate.parse("2026-11-15")));
    }

    @Test
    void aChargedNodeListsTheValuesItReadOfItselfAndItsChildren() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.uses()).containsExactlyInAnyOrder(new ValueUse(A, BEFORE), new ValueUse(A, TO),
                new ValueUse(M1, BEFORE), new ValueUse(M1, TO), new ValueUse(M2, BEFORE), new ValueUse(M2, TO));
    }

    @Test
    void aPlotWithoutConsumptionGetsNothing() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 700),
                plot(M1, P1, A, 600), plot(M2, P2, A, 0));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.shares()).containsExactly(Map.entry(P1, kwh(100)));
    }

    @Test
    void zeroLossesChargeNobodyWithoutAWarning() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 600),
                plot(M1, P1, A, 600));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(a.shares()).isEmpty();
        assertThat(a.noPlotConsumption()).isFalse();
    }

    @Test
    void lossesWithoutAnyPlotConsumptionChargeNobodyAndSaySo() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 100),
                consumer(LIGHT, "Освітлення", A, 40));

        NodeResult a = only(LossesCalculator.calculate(network, FROM, TO));

        assertThat(a.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(a.losses()).isEqualTo(kwh(60));
        assertThat(a.noPlotConsumption()).isTrue();
    }

    @Test
    void aPlotMovedBetweenQuartersCountsUnderEachForItsDays() {
        // P1 is under A until 15.10 and under B from 16.10; it uses 10 kWh a day (310 in October)
        UUID meter = UUID.randomUUID();
        MeterHistory daily = new MeterHistory(INSTALLED, MeterValue.ZERO, null, null, List.of(
                reading("2026-09-30", "1000"), reading("2026-10-15", "1150"), reading("2026-10-31", "1310")));
        NetworkChain p1 = new NetworkChain(meter, MeterKind.PLOT, P1, meter, null, List.of(meter), List.of(daily),
                List.of(new Placement(A, INSTALLED, LocalDate.parse("2026-10-15")),
                        new Placement(B, LocalDate.parse("2026-10-16"), null)));
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 200),
                node(B, MeterKind.QUARTER, "Квартал Б", null, 180), p1);

        Map<UUID, NodeResult> nodes = byNode(LossesCalculator.calculate(network, FROM, TO));

        assertThat(nodes.get(A).children()).isEqualTo(kwh(150));
        assertThat(nodes.get(B).children()).isEqualTo(kwh(160));
        assertThat(nodes.get(A).shares()).containsExactly(Map.entry(P1, kwh(50)));
        assertThat(nodes.get(B).shares()).containsExactly(Map.entry(P1, kwh(20)));
    }

    @Test
    void aQuarterWithoutAMeterUnderItInTheRangeIsNotANode() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 100),
                chain(M1, MeterKind.PLOT, P1, null, null, history(INSTALLED, 0, BEFORE, 0, TO, 50)));

        assertThat(LossesCalculator.calculate(network, FROM, TO)).isEmpty();
    }

    @Test
    void aRangeMustNotEndBeforeItStarts() {
        assertThatThrownBy(() -> LossesCalculator.calculate(List.of(), TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- planning

    @Test
    void aNodeChargedByAnotherRunChargesNothing() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400));

        Plan plan = plan(network, Map.of(A, new Overlap(UUID.randomUUID(), FROM, TO)), electricity("4.3200"));

        assertThat(plan.charges()).isEmpty();
        assertThat(plan.overlapping()).hasSize(1);
        assertThat(plan.nodes().getFirst().shares()).isEmpty();
    }

    @Test
    void aPlotWithoutATariffForEveryDayIsListedWithTheReasonAndNotCharged() {
        // the electricity tariff starts on 15.10
        TariffEntry late = new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Електроенергія", Scope.ALL_PLOTS,
                Set.of(), Set.of(), List.of(version("2026-10-15", "4.3200")));
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400));

        Plan plan = plan(network, Map.of(), late);

        assertThat(plan.charges()).isEmpty();
        PlannedNode a = plan.nodes().getFirst();
        assertThat(a.shares()).extracting(LossesPlanning.PlannedShare::reason)
                .containsOnly(Reason.NO_TARIFF_FOR_ALL_DAYS);
        assertThat(a.shares()).extracting(LossesPlanning.PlannedShare::amount).containsOnlyNulls();
    }

    @Test
    void theDigestFollowsWhatTheRunBooks() {
        List<NetworkChain> network = List.of(node(A, MeterKind.QUARTER, "Квартал А", null, 1100),
                plot(M1, P1, A, 600), plot(M2, P2, A, 400));
        TariffEntry tariff = electricity("4.3200");

        String first = plan(network, Map.of(), tariff).digest();

        assertThat(plan(network, Map.of(), tariff).digest()).isEqualTo(first);
        assertThat(plan(network, Map.of(), electricity("5.0000")).digest()).isNotEqualTo(first);
    }

    // ---- fixtures

    /** Substation 2 300 with A 1 100 (P1 600, P2 400), B (P3 1 000) and the well 50; B read after the range or not. */
    private static List<NetworkChain> substation(int b, boolean bKnown) {
        List<NetworkChain> network = new ArrayList<>();
        network.add(node(S, MeterKind.SUBSTATION, "ТП-1", null, 2300));
        network.add(node(A, MeterKind.QUARTER, "Квартал А", S, 1100));
        network.add(bKnown ? node(B, MeterKind.QUARTER, "Квартал Б", S, b)
                : chain(B, MeterKind.QUARTER, null, "Квартал Б", S, history(INSTALLED, 0, BEFORE, 5000)));
        network.add(consumer(WELL, "Свердловина", S, 50));
        network.add(plot(M1, P1, A, 600));
        network.add(plot(M2, P2, A, 400));
        network.add(plot(M3, P3, B, 1000));
        return network;
    }

    private static NetworkChain node(UUID id, MeterKind kind, String name, UUID parent, int consumption) {
        return chain(id, kind, null, name, parent, history(INSTALLED, 0, BEFORE, 5000, TO, 5000 + consumption));
    }

    private static NetworkChain consumer(UUID id, String name, UUID parent, int consumption) {
        return chain(id, MeterKind.COMMON_CONSUMER, null, name, parent,
                history(INSTALLED, 0, BEFORE, 300, TO, 300 + consumption));
    }

    private static NetworkChain plot(UUID meter, UUID plot, UUID parent, int consumption) {
        return chain(meter, MeterKind.PLOT, plot, null, parent,
                history(INSTALLED, 0, BEFORE, 1000, TO, 1000 + consumption));
    }

    private static NetworkChain chain(UUID meter, MeterKind kind, UUID plot, String name, UUID parent,
            MeterHistory history) {
        return new NetworkChain(meter, kind, plot, meter, name, List.of(meter), List.of(history),
                List.of(new Placement(parent, INSTALLED, null)));
    }

    /** A meter installed with {@code initial}, then readings as pairs of date and value. */
    private static MeterHistory history(LocalDate installed, int initial, Object... readings) {
        List<Reading> list = new ArrayList<>();
        for (int i = 0; i < readings.length; i += 2) {
            list.add(new Reading((LocalDate) readings[i], MeterValue.of(BigDecimal.valueOf((Integer) readings[i + 1]))));
        }
        return new MeterHistory(installed, MeterValue.of(BigDecimal.valueOf(initial)), null, null, list);
    }

    private static Reading reading(String on, String value) {
        return new Reading(LocalDate.parse(on), MeterValue.parse(value));
    }

    private static Quantity kwh(int value) {
        return value < 0 ? Quantity.ZERO.minus(Quantity.of(MeterValue.of(BigDecimal.valueOf(-value))))
                : Quantity.of(MeterValue.of(BigDecimal.valueOf(value)));
    }

    private static NodeResult only(List<NodeResult> results) {
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static Map<UUID, NodeResult> byNode(List<NodeResult> results) {
        return results.stream().collect(Collectors.toMap(NodeResult::nodeKey, Function.identity()));
    }

    private static TariffEntry electricity(String rate) {
        return new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Електроенергія", Scope.ALL_PLOTS, Set.of(), Set.of(),
                List.of(version("2026-01-01", rate)));
    }

    private static VersionRef version(String validFrom, String rate) {
        return new VersionRef(UUID.randomUUID(), new VersionTerms(LocalDate.parse(validFrom), Basis.PER_KWH,
                Frequency.MONTHLY, Rate.parse(rate), null));
    }

    /** Plans every node of the network with the tariff; {@code overlaps} by node. */
    private static Plan plan(List<NetworkChain> network, Map<UUID, Overlap> overlaps, TariffEntry tariff) {
        Map<UUID, NetworkChain> chains = network.stream()
                .collect(Collectors.toMap(NetworkChain::key, Function.identity()));
        List<PlannedInput> inputs = LossesCalculator.calculate(network, FROM, TO).stream()
                .map(r -> new PlannedInput(chains.get(r.nodeKey()), r, overlaps.get(r.nodeKey()))).toList();
        Map<UUID, PlotFacts> facts = new HashMap<>();
        for (UUID plot : List.of(P1, P2, P3)) {
            facts.put(plot, new PlotFacts(plot, false, false, new BigDecimal("600")));
        }
        Snapshot snapshot = new Snapshot(List.of(new ChargeTypeEntry(ELECTRICITY, "ELECTRICITY", true, false)),
                List.of(tariff));
        return LossesPlanning.plan(FROM, TO, inputs, snapshot, ELECTRICITY, facts);
    }

    private static Map<UUID, String> charges(Plan plan) {
        Map<UUID, String> amounts = new LinkedHashMap<>();
        plan.charges().forEach(charge -> amounts.put(charge.plotId(), charge.amount().toString()));
        return amounts;
    }
}
