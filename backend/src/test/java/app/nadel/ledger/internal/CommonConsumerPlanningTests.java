package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.internal.CommonConsumerPlanning.ConsumerInput;
import app.nadel.ledger.internal.CommonConsumerPlanning.Outcome;
import app.nadel.ledger.internal.CommonConsumerPlanning.Overlap;
import app.nadel.ledger.internal.CommonConsumerPlanning.Plan;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedCharge;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedLine;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedShare;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterNetwork.Placement;
import app.nadel.meters.MeterValue;
import app.nadel.money.Rate;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Scope;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffBook.VersionRef;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a common consumer run books (feature 011 §2.6): the amounts of the common consumer rows of the feature's worked
 * examples (equal 54.00, connected plots 86.40, plot-days 24/24/12 kWh, users 43.20, not charged), the price of a share
 * spread over the plot's own days, the outcomes, the missing tariff, the plots that cannot be priced, overlapping runs,
 * the values read and the digest.
 */
class CommonConsumerPlanningTests {

    private static final UUID COMMON = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final LocalDate INSTALLED = LocalDate.parse("2026-01-01");
    private static final LocalDate SEP_30 = LocalDate.parse("2026-09-30");
    private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
    private static final LocalDate OCT_16 = LocalDate.parse("2026-10-16");
    private static final LocalDate OCT_30 = LocalDate.parse("2026-10-30");
    private static final LocalDate OCT_31 = LocalDate.parse("2026-10-31");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID P4 = UUID.fromString("00000000-0000-0000-0000-000000000004");

    // ---- the worked examples

    @Test
    void lightingEquallyPerPlotChargesEachOfFourPlots54() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1050);

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2, P3, P4)));

        assertThat(amounts(plan)).containsExactly("1|12.500|54.00", "2|12.500|54.00", "3|12.500|54.00",
                "4|12.500|54.00");
        assertThat(plan.total()).hasToString("216.00");
        assertThat(plan.plots()).isEqualTo(4);
        PlannedLine line = plan.lines().getFirst();
        assertThat(line.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(line.consumption().toMeterValue()).hasToString("50.000");
        assertThat(line.amount()).hasToString("216.00");
        assertThat(line.plotDays()).isEqualTo(4 * 31);
    }

    @Test
    void theWellAmongConnectedPlotsCharges86ForEachConnectedPlotAndNothingForTheOther() {
        NetworkChain well = consumer("Свердловина №1", 300, OCT_31, 360);
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        connected.put(P1, DaySet.of(OCT_1, OCT_31));
        connected.put(P2, DaySet.of(OCT_1, OCT_31));
        connected.put(P3, DaySet.of(OCT_1, OCT_31));

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(well, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_31, connected));

        assertThat(amounts(plan)).containsExactly("1|20.000|86.40", "2|20.000|86.40", "3|20.000|86.40");
    }

    @Test
    void aPlotConnectedFromTheMiddlePaysForItsPlotDays() {
        NetworkChain well = consumer("Свердловина №1", 300, OCT_30, 360);
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        connected.put(P1, DaySet.of(OCT_1, OCT_30));
        connected.put(P2, DaySet.of(OCT_1, OCT_30));
        connected.put(P3, DaySet.of(OCT_16, OCT_30));

        Plan plan = plan(OCT_1, OCT_30, tariff("4.3200"),
                input(well, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_30, connected));

        // 24 x 4.32 = 103.68; 12 x 4.32 = 51.84
        assertThat(amounts(plan)).containsExactly("1|24.000|103.68", "2|24.000|103.68", "3|12.000|51.84");
        assertThat(plan.lines().getFirst().plotDays()).isEqualTo(75);
        PlannedCharge p3 = plan.charges().get(2);
        assertThat(p3.parts()).singleElement().satisfies(part -> {
            assertThat(part.days()).isEqualTo(15);
            assertThat(part.from()).isEqualTo(OCT_16);
            assertThat(part.to()).isEqualTo(OCT_30);
            assertThat(part.quantity().toMeterValue()).hasToString("12.000");
        });
    }

    @Test
    void serviceUsersPay43AndOtherPlotsNothing() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1030);

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(lighting, CommonConsumerSplit.SERVICE_USERS, OCT_1, OCT_31, whole(P1, P2, P4)));

        assertThat(amounts(plan)).containsExactly("1|10.000|43.20", "2|10.000|43.20", "4|10.000|43.20");
    }

    @Test
    void aConsumerThatIsNotChargedIsListedWithItsConsumptionAndChargesNothing() {
        NetworkChain office = consumer("Правління", 1000, OCT_31, 1030);

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(office, CommonConsumerSplit.NOT_CHARGED, OCT_1, OCT_31, whole(P1, P2)));

        PlannedLine line = plan.lines().getFirst();
        assertThat(line.outcome()).isEqualTo(Outcome.NOT_CHARGED);
        assertThat(line.consumption().toMeterValue()).hasToString("30.000");
        assertThat(line.shares()).isEmpty();
        assertThat(line.uses()).isEmpty();
        assertThat(plan.charges()).isEmpty();
    }

    // ---- the price of a share

    @Test
    void aShareIsPricedOverThePlotsOwnDaysWhenTheRateChanges() {
        // 0.8 kWh per plot-day; P3 connected 16.10-30.10: 5 days at 4.32 (to 20.10) and 10 days at 5.00
        NetworkChain well = consumer("Свердловина №1", 300, OCT_30, 360);
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        connected.put(P1, DaySet.of(OCT_1, OCT_30));
        connected.put(P2, DaySet.of(OCT_1, OCT_30));
        connected.put(P3, DaySet.of(OCT_16, OCT_30));
        TariffEntry tariff = new TariffEntry(UUID.randomUUID(), COMMON, "Спільні", Scope.ALL_PLOTS, Set.of(),
                Set.of(), List.of(version("2026-01-01", "4.3200"), version("2026-10-21", "5.0000")));

        Plan plan = plan(OCT_1, OCT_30, tariff,
                input(well, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_30, connected));

        // P3: 4 kWh x 4.32 + 8 kWh x 5.00 = 17.28 + 40.00 = 57.28 (spread over the whole range it would be 54.56)
        // P1: 16 kWh x 4.32 + 8 kWh x 5.00 = 69.12 + 40.00 = 109.12
        assertThat(amounts(plan)).containsExactly("1|24.000|109.12", "2|24.000|109.12", "3|12.000|57.28");
        assertThat(plan.charges().get(2).parts()).extracting(part -> part.days() + "|" + part.from() + "|" + part.to()
                + "|" + part.quantity().toMeterValue()).containsExactly("5|2026-10-16|2026-10-20|4.000",
                        "10|2026-10-21|2026-10-30|8.000");
    }

    @Test
    void aShareIsRoundedOnceAndAShareOfZeroAmountChargesNothing() {
        // 10 kWh among 3 plots: 3.333... kWh x 4.32 = 14.40 each, from the exact third
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1010);

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2, P3)));

        assertThat(amounts(plan)).containsExactly("1|3.333|14.40", "2|3.333|14.40", "3|3.333|14.40");

        // 0.001 kWh among 3 plots rounds to 0.00: nothing is charged
        NetworkChain tiny = new NetworkChain(UUID.randomUUID(), MeterKind.COMMON_CONSUMER, null, UUID.randomUUID(),
                "Датчик", List.of(UUID.randomUUID()), List.of(new MeterHistory(INSTALLED, MeterValue.ZERO, null, null,
                        List.of(new Reading(SEP_30, MeterValue.parse("1.000")),
                                new Reading(OCT_31, MeterValue.parse("1.001"))))), List.of());
        Plan small = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(tiny, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2, P3)));
        assertThat(small.charges()).isEmpty();
        assertThat(small.lines().getFirst().shares()).extracting(PlannedShare::amount).containsOnlyNulls();
    }

    @Test
    void aPlotThatCannotBePricedIsListedWithTheReasonAndNotCharged() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1040);
        TariffEntry tariff = new TariffEntry(UUID.randomUUID(), COMMON, "Спільні", Scope.ALL_PLOTS, Set.of(),
                Set.of(P2), List.of(version("2026-01-01", "4.3200")));
        TariffEntry late = new TariffEntry(UUID.randomUUID(), COMMON, "Спільні Б", Scope.SELECTED_PLOTS, Set.of(P3),
                Set.of(), List.of(version("2026-10-10", "4.3200")));

        Plan plan = CommonConsumerPlanning.plan(OCT_1, OCT_31,
                List.of(input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2, P3, P4))),
                new Snapshot(List.of(new ChargeTypeEntry(COMMON, "COMMON_CONSUMERS", true, false)),
                        List.of(tariff, late)), facts(P1, P2, P3));

        // P2 excluded; P3 in two tariffs that would both charge it; P4 unknown to the plots (not chargeable)
        assertThat(plan.lines().getFirst().shares()).extracting(s -> s.plotId() + "|" + s.amount() + "|" + s.reason())
                .containsExactly(P1 + "|43.20|null", P2 + "|null|" + Reason.EXCLUDED,
                        P3 + "|null|" + Reason.CONFLICTING_TARIFFS, P4 + "|null|" + Reason.OUTSIDE_SCOPE);
        assertThat(plan.charges()).extracting(PlannedCharge::plotId).containsExactly(P1);
    }

    // ---- the outcomes

    @Test
    void anUnknownConsumerIsSkippedWithItsLastReading() {
        NetworkChain well = consumer("Свердловина №1", 300, null, null);

        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(well, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2)));

        PlannedLine line = plan.lines().getFirst();
        assertThat(line.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(line.consumption()).isNull();
        assertThat(line.unknown()).isNotNull();
        assertThat(line.unknown().meterId()).isEqualTo(well.meterIds().getFirst());
        assertThat(line.unknown().lastReadingOn()).isEqualTo(SEP_30);
        assertThat(line.charges()).isFalse();
        assertThat(plan.charges()).isEmpty();
    }

    @Test
    void aConsumerThatUsedSomethingWithNobodyToShareItHasNoPlotsAndZeroConsumptionCharges() {
        NetworkChain well = consumer("Свердловина №1", 300, OCT_31, 360);
        Plan nobody = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(well, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_31, Map.of()));
        assertThat(nobody.lines().getFirst().outcome()).isEqualTo(Outcome.NO_PLOTS);
        assertThat(nobody.lines().getFirst().charges()).isFalse();

        NetworkChain idle = consumer("Свердловина №2", 300, OCT_31, 300);
        Plan zero = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(idle, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_31, Map.of()));
        assertThat(zero.lines().getFirst().outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(zero.lines().getFirst().charges()).isTrue();
        assertThat(zero.charges()).isEmpty();
    }

    @Test
    void withoutACommonConsumersTariffThePlotsAreListedWithoutAmounts() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1050);
        ConsumerInput input = input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2));

        // no tariff of the type at all
        Plan none = CommonConsumerPlanning.plan(OCT_1, OCT_31, List.of(input),
                new Snapshot(List.of(new ChargeTypeEntry(COMMON, "COMMON_CONSUMERS", true, false)), List.of()),
                facts(P1, P2));
        assertThat(none.noTariff()).isTrue();
        assertThat(none.charges()).isEmpty();
        assertThat(none.lines().getFirst().shares()).extracting(s -> s.kwh().toMeterValue() + "|" + s.amount())
                .containsExactly("25.000|null", "25.000|null");

        // a tariff whose only version starts after the range, or one by plot
        TariffEntry later = new TariffEntry(UUID.randomUUID(), COMMON, "Спільні", Scope.ALL_PLOTS, Set.of(),
                Set.of(), List.of(version("2026-11-01", "4.3200")));
        assertThat(CommonConsumerPlanning.noTariff(snapshot(later), COMMON, OCT_1, OCT_31)).isTrue();
        TariffEntry perPlot = new TariffEntry(UUID.randomUUID(), COMMON, "Спільні", Scope.ALL_PLOTS, Set.of(),
                Set.of(), List.of(new VersionRef(UUID.randomUUID(), new VersionTerms(INSTALLED, Basis.PER_PLOT,
                        Frequency.MONTHLY, Rate.parse("10.0000"), null))));
        assertThat(CommonConsumerPlanning.noTariff(snapshot(perPlot), COMMON, OCT_1, OCT_31)).isTrue();
        // an inactive type
        assertThat(CommonConsumerPlanning.noTariff(new Snapshot(List.of(new ChargeTypeEntry(COMMON,
                "COMMON_CONSUMERS", false, false)), List.of(tariff("4.3200"))), COMMON, OCT_1, OCT_31)).isTrue();
        // a per-kWh version valid in the range prices it
        assertThat(CommonConsumerPlanning.noTariff(snapshot(tariff("4.3200")), COMMON, OCT_1, OCT_31)).isFalse();
    }

    @Test
    void aConsumerChargedByABookedRunChargesNothingAndIsLeftOutOfTheDigest() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1050);
        NetworkChain well = consumer("Свердловина №1", 300, OCT_31, 360);
        Overlap overlap = new Overlap(UUID.randomUUID(), SEP_30, OCT_16);
        ConsumerInput charged = new ConsumerInput(lighting, CommonConsumerSplit.EQUAL_PER_PLOT,
                LossesCalculator.measure(lighting, OCT_1, OCT_31), whole(P1, P2), overlap);
        ConsumerInput open = input(well, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2));

        Plan both = plan(OCT_1, OCT_31, tariff("4.3200"), charged, open);
        Plan other = plan(OCT_1, OCT_31, tariff("4.3200"), open);

        assertThat(both.overlapping()).extracting(line -> line.chain().key()).containsExactly(lighting.key());
        assertThat(both.lines().getFirst().charges()).isFalse();
        assertThat(both.lines().getFirst().shares()).isEmpty();
        assertThat(both.charges()).extracting(PlannedCharge::consumerKey).containsOnly(well.key());
        assertThat(both.digest()).isEqualTo(other.digest());
    }

    @Test
    void aChargedConsumerReadsTheValuesAroundTheRangeAndTheDigestFollowsTheCharges() {
        NetworkChain lighting = consumer("Освітлення", 1000, OCT_31, 1050);
        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, whole(P1, P2)));

        UUID meter = lighting.meterIds().getFirst();
        assertThat(plan.lines().getFirst().uses()).containsExactlyInAnyOrder(
                new LossesCalculator.ValueUse(meter, SEP_30), new LossesCalculator.ValueUse(meter, OCT_31));
        Plan more = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(consumer("Освітлення", 1000, OCT_31, 1052), CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31,
                        whole(P1, P2)));
        assertThat(more.digest()).isNotEqualTo(plan.digest());
        assertThat(plan(OCT_1, OCT_31, tariff("4.3200"), input(lighting, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1,
                OCT_31, whole(P1, P2))).digest()).isEqualTo(plan.digest());
    }

    @Test
    void theCalculationOfAChargeNamesTheRunTheConsumerAndTheSplit() {
        NetworkChain well = consumer("Свердловина №1", 300, OCT_31, 360);
        Plan plan = plan(OCT_1, OCT_31, tariff("4.3200"),
                input(well, CommonConsumerSplit.CONNECTED_PLOTS, OCT_1, OCT_31, Map.of(P1, DaySet.of(OCT_1, OCT_31))));
        UUID run = UUID.randomUUID();

        Map<String, Object> calculation = plan.charges().getFirst().calculation(run);

        assertThat(calculation).containsEntry("amount", "259.20").containsEntry("runId", run.toString())
                .containsEntry("consumerMeterId", well.key().toString())
                .containsEntry("consumerName", "Свердловина №1").containsEntry("split", "CONNECTED_PLOTS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) calculation.get("parts");
        assertThat(parts).singleElement().satisfies(part -> assertThat(part).containsEntry("quantity", "60.000")
                .containsEntry("unit", "KWH").containsEntry("rate", "4.3200").containsEntry("days", 31L)
                .containsEntry("calculated", false));
    }

    // ---- fixtures

    /** A common consumer meter read on 30.09 and, when {@code on} is given, on that day. */
    private static NetworkChain consumer(String name, int before, LocalDate on, Integer after) {
        UUID meter = UUID.randomUUID();
        List<Reading> readings = new ArrayList<>();
        readings.add(new Reading(SEP_30, MeterValue.of(BigDecimal.valueOf(before))));
        if (on != null) {
            readings.add(new Reading(on, MeterValue.of(BigDecimal.valueOf(after))));
        }
        return new NetworkChain(meter, MeterKind.COMMON_CONSUMER, null, meter, name, List.of(meter),
                List.of(new MeterHistory(INSTALLED, MeterValue.ZERO, null, null, readings)),
                List.of(new Placement(null, INSTALLED, null)));
    }

    private static ConsumerInput input(NetworkChain chain, CommonConsumerSplit split, LocalDate from, LocalDate to,
            Map<UUID, DaySet> eligible) {
        return new ConsumerInput(chain, split, LossesCalculator.measure(chain, from, to), eligible, null);
    }

    private static Map<UUID, DaySet> whole(UUID... plots) {
        Map<UUID, DaySet> eligible = new LinkedHashMap<>();
        for (UUID plot : plots) {
            eligible.put(plot, DaySet.ALL);
        }
        return eligible;
    }

    private static Plan plan(LocalDate from, LocalDate to, TariffEntry tariff, ConsumerInput... inputs) {
        return CommonConsumerPlanning.plan(from, to, List.of(inputs), snapshot(tariff), facts(P1, P2, P3, P4));
    }

    private static Snapshot snapshot(TariffEntry tariff) {
        return new Snapshot(List.of(new ChargeTypeEntry(COMMON, "COMMON_CONSUMERS", true, false)), List.of(tariff));
    }

    private static Map<UUID, PlotFacts> facts(UUID... plots) {
        Map<UUID, PlotFacts> facts = new LinkedHashMap<>();
        for (UUID plot : plots) {
            facts.put(plot, new PlotFacts(plot, false, false, new BigDecimal("600")));
        }
        return facts;
    }

    private static TariffEntry tariff(String rate) {
        return new TariffEntry(UUID.randomUUID(), COMMON, "Спільні споживачі", Scope.ALL_PLOTS, Set.of(), Set.of(),
                List.of(version("2026-01-01", rate)));
    }

    private static VersionRef version(String validFrom, String rate) {
        return new VersionRef(UUID.randomUUID(), new VersionTerms(LocalDate.parse(validFrom), Basis.PER_KWH,
                Frequency.MONTHLY, Rate.parse(rate), null));
    }

    /** Every charge as {@code plotNumber|kWh|amount}, the plots numbered by the last digit of their id. */
    private static List<String> amounts(Plan plan) {
        return plan.charges().stream().map(charge -> charge.plotId().toString().substring(35) + "|"
                + charge.kwh().toMeterValue() + "|" + charge.amount()).toList();
    }
}
