package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.RecalculationFixtures.ELECTRICITY;
import static app.nadel.ledger.internal.RecalculationFixtures.MEMBERSHIP;
import static app.nadel.ledger.internal.RecalculationFixtures.METER;
import static app.nadel.ledger.internal.RecalculationFixtures.NOV;
import static app.nadel.ledger.internal.RecalculationFixtures.NOVEMBER_20;
import static app.nadel.ledger.internal.RecalculationFixtures.NOW;
import static app.nadel.ledger.internal.RecalculationFixtures.OCT;
import static app.nadel.ledger.internal.RecalculationFixtures.OCTOBER_AND_NOVEMBER_OPEN;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT_2;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT_600;
import static app.nadel.ledger.internal.RecalculationFixtures.SEP;
import static app.nadel.ledger.internal.RecalculationFixtures.SEPTEMBER_CLOSED;
import static app.nadel.ledger.internal.RecalculationFixtures.TENANT;
import static app.nadel.ledger.internal.RecalculationFixtures.TODAY;
import static app.nadel.ledger.internal.RecalculationFixtures.WATER;
import static app.nadel.ledger.internal.RecalculationFixtures.describe;
import static app.nadel.ledger.internal.RecalculationFixtures.electricityTariff;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedElectricity;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedMembership;
import static app.nadel.ledger.internal.RecalculationFixtures.facts;
import static app.nadel.ledger.internal.RecalculationFixtures.interval;
import static app.nadel.ledger.internal.RecalculationFixtures.lossesPlan;
import static app.nadel.ledger.internal.RecalculationFixtures.membership;
import static app.nadel.ledger.internal.RecalculationFixtures.perKwh;
import static app.nadel.ledger.internal.RecalculationFixtures.perM3;
import static app.nadel.ledger.internal.RecalculationFixtures.perSotka;
import static app.nadel.ledger.internal.RecalculationFixtures.plan;
import static app.nadel.ledger.internal.RecalculationFixtures.planInterval;
import static app.nadel.ledger.internal.RecalculationFixtures.point;
import static app.nadel.ledger.internal.RecalculationFixtures.snapshot;
import static app.nadel.ledger.internal.RecalculationFixtures.tariff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.ReadingChargePlanning.Failed;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.RecalculationFixtures.Ledger;
import app.nadel.ledger.internal.RecalculationPlanner.Line;
import app.nadel.ledger.internal.RecalculationPlanner.Plan;
import app.nadel.ledger.internal.RecalculationPlanner.QueueOutcome;
import app.nadel.ledger.internal.RecalculationPlanner.RunAction;
import app.nadel.ledger.internal.RecalculationPlanner.RunOutcome;
import app.nadel.ledger.internal.RecalculationRecord.Cause;
import app.nadel.meters.MeterResource;
import app.nadel.money.Money;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.RecalculationChangeReason;
import org.junit.jupiter.api.Test;

/**
 * The recalculation planner (feature 012, functional spec 2.1): one test per row of the requirement's worked examples
 * (§3), named after the row, plus frozen chains, the queue, runs whose lines all go, a meter moved to another plot and
 * the guards. Every booked and every expected amount comes from the 011 planners; the planner itself only subtracts.
 * The rows "stale preview", "correction that changes nothing" and "electrician" are about booking and the API (slices
 * 3, 6 and 7).
 */
class RecalculationPlannerTests {

    private static final BigDecimal FIVE_PERCENT = new BigDecimal("5.00");

    private final Ledger ledger = new Ledger();
    private final UUID tariffId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    // ---- requirement §3, tariff rows

    @Test
    void wrongRateCorrected60To50SeptemberClosedOctoberOpen() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "60.0000")));
        ChargeRecord september = ledger.membership(before, PLOT_600, SEP, SEP);
        ChargeRecord october = ledger.membership(before, PLOT_600, OCT, OCT);
        assertThat(september.getAmount()).hasToString("30.00");
        assertThat(october.getAmount()).hasToString("30.00");

        // the version is corrected in place: same id, rate 50.0000
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "50.0000")));
        Plan plan = plan(ledger.rows(), expectedMembership(after, PLOT_600, SEP, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(describe(plan)).containsExactly(
                "ADJUST 2026-09-01 30.00->25.00 -5.00 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-10-01 30.00->25.00 -5.00 in 2026-10");
        Line adjustment = plan.lines().get(0);
        Line october2 = plan.lines().get(1);
        assertThat(adjustment.original().id()).isEqualTo(september.getId());
        assertThat(adjustment.serviceTo()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(adjustment.recharge()).isNull();
        assertThat(october2.original().id()).isEqualTo(october.getId());
        assertThat(october2.recharge().amount()).hasToString("25.00");
        assertThat(adjustment.reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
        assertThat(october2.reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
        // after booking: 30.00 - 5.00 (September) + 30.00 - 30.00 + 25.00 (October)
        Money total = september.getAmount().plus(adjustment.difference()).plus(october.getAmount())
                .minus(october.getAmount()).plus(october2.recharge().amount());
        assertThat(total).hasToString("50.00");
        assertThat(plan.total()).hasToString("-10.00");
    }

    @Test
    void backDatedVersionOpenMonthOnly() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ledger.membership(before, PLOT_600, SEP, SEP);
        ledger.membership(before, PLOT_600, OCT, OCT);

        // a new version 60.0000 from 01.10 entered on 20.10
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000"),
                perSotka(UUID.randomUUID(), "2026-10-01", "60.0000")));
        Plan plan = plan(ledger.rows(), expectedMembership(after, PLOT_600, SEP, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(describe(plan)).containsExactly("REVERSE_AND_RECHARGE 2026-10-01 25.00->30.00 5.00 in 2026-10");
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
    }

    @Test
    void backDatedVersionIntoAClosedMonth() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ledger.membership(before, PLOT_600, SEP, SEP);
        ledger.membership(before, PLOT_600, OCT, OCT);

        // a new version 60.0000 from 16.09 (30-day September): 12.50 + 15.00
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000"),
                perSotka(UUID.randomUUID(), "2026-09-16", "60.0000")));
        List<Expected> expected = expectedMembership(after, PLOT_600, SEP, OCT);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.TARIFF_VERSION);

        assertThat(describe(plan)).containsExactly(
                "ADJUST 2026-09-01 25.00->27.50 2.50 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-10-01 25.00->30.00 5.00 in 2026-10");
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
    }

    @Test
    void versionDeletedTheTariffNoLongerAppliesInOctober() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-10-01", "50.0000"),
                perSotka(UUID.randomUUID(), "2026-11-01", "50.0000")));
        ledger.membership(before, PLOT_600, OCT, OCT);

        // the only October version deleted: the tariff starts in November
        Snapshot after = snapshot(membership(tariffId, perSotka(UUID.randomUUID(), "2026-11-01", "50.0000")));
        Plan plan = plan(ledger.rows(), expectedMembership(after, PLOT_600, SEP, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(describe(plan)).containsExactly("REVERSE 2026-10-01 25.00->0.00 -25.00 in 2026-10");
        assertThat(plan.lines().getFirst().recharge()).isNull();
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
    }

    // ---- requirement §3, reading rows

    @Test
    void readingCorrectedBothIntervalsOpen() {
        Snapshot electricity = snapshot(electricityTariff());
        var r1 = point("2026-09-03", "8140", "R1");
        var r2 = point("2026-10-05", "8205", "R2");
        var r3 = point("2026-11-05", "8270", "R3");
        ChargeRecord first = ledger.electricity(electricity, interval(r1, r2), null, OCT).getFirst();
        ChargeRecord second = ledger.electricity(electricity, interval(r2, r3), null, NOV).getFirst();
        assertThat(first.getAmount()).hasToString("280.80");
        assertThat(second.getAmount()).hasToString("280.80");

        // 05.10: 8 205 -> 8 215, a new reading with a new key on the same date
        var corrected = point("2026-10-05", "8215", "R2-corrected");
        List<Expected> expected = new ArrayList<>(expectedElectricity(electricity, interval(r1, corrected), null));
        expected.addAll(expectedElectricity(electricity, interval(corrected, r3), null));
        Plan plan = plan(ledger.rows(), expected, OCTOBER_AND_NOVEMBER_OPEN, NOVEMBER_20, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "REVERSE_AND_RECHARGE 2026-09-04 280.80->324.00 43.20 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-10-06 280.80->237.60 -43.20 in 2026-11");
        assertThat(plan.lines()).allSatisfy(line -> assertThat(line.reasons())
                .containsExactly(RecalculationChangeReason.READING));
    }

    @Test
    void readingCorrectedFirstIntervalInAClosedMonth() {
        // the requirement's readings a month earlier, so that the first interval is booked in closed September
        Snapshot electricity = snapshot(electricityTariff());
        var r1 = point("2026-08-03", "8140", "R1");
        var r2 = point("2026-09-05", "8205", "R2");
        var r3 = point("2026-10-05", "8270", "R3");
        ledger.electricity(electricity, interval(r1, r2), null, SEP);
        ledger.electricity(electricity, interval(r2, r3), null, OCT);

        var corrected = point("2026-09-05", "8215", "R2-corrected");
        List<Expected> expected = new ArrayList<>(expectedElectricity(electricity, interval(r1, corrected), null));
        expected.addAll(expectedElectricity(electricity, interval(corrected, r3), null));
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "ADJUST 2026-08-04 280.80->324.00 43.20 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-09-06 280.80->237.60 -43.20 in 2026-10");
    }

    @Test
    void readingDeletedIntervalsMerge() {
        Snapshot electricity = snapshot(electricityTariff());
        var r1 = point("2026-09-03", "8140", "R1");
        var r2 = point("2026-10-05", "8205", "R2");
        var r3 = point("2026-11-05", "8270", "R3");
        ledger.electricity(electricity, interval(r1, r2), null, OCT);
        ledger.electricity(electricity, interval(r2, r3), null, NOV);

        // 05.10 deleted: one interval 03.09 -> 05.11, 130 kWh
        Plan plan = plan(ledger.rows(), expectedElectricity(electricity, interval(r1, r3), null),
                OCTOBER_AND_NOVEMBER_OPEN, NOVEMBER_20, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "REVERSE 2026-09-04 280.80->0.00 -280.80 in 2026-10",
                "NEW 2026-09-04 0.00->561.60 561.60 in 2026-11",
                "REVERSE 2026-10-06 280.80->0.00 -280.80 in 2026-11");
        assertThat(plan.total()).isEqualTo(Money.ZERO);
        Line merged = plan.lines().get(1);
        assertThat(merged.original()).isNull();
        assertThat(merged.recharge().amount()).hasToString("561.60");
        assertThat(merged.reasons()).containsExactly(RecalculationChangeReason.READING);
    }

    @Test
    void readingDeletedOneIntervalInAClosedMonth() {
        Snapshot electricity = snapshot(electricityTariff());
        var r1 = point("2026-08-03", "8140", "R1");
        var r2 = point("2026-09-05", "8205", "R2");
        var r3 = point("2026-10-05", "8270", "R3");
        ledger.electricity(electricity, interval(r1, r2), null, SEP);
        ledger.electricity(electricity, interval(r2, r3), null, OCT);

        Plan plan = plan(ledger.rows(), expectedElectricity(electricity, interval(r1, r3), null), SEPTEMBER_CLOSED,
                TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "ADJUST 2026-08-04 280.80->0.00 -280.80 in 2026-10",
                "NEW 2026-08-04 0.00->561.60 561.60 in 2026-10",
                "REVERSE 2026-09-06 280.80->0.00 -280.80 in 2026-10");
    }

    @Test
    void backDatedTariffChargesAQueuedInterval() {
        MeterInterval queued = interval(point("2026-09-20", "8140", "R1"), point("2026-10-05", "8205", "R2"));
        UUID electricityTariff = UUID.randomUUID();
        UUID october = UUID.randomUUID();
        Snapshot before = snapshot(tariff(electricityTariff, ELECTRICITY, perKwh(october, "2026-10-01", "4.3200")));
        assertThat(planInterval(before, ELECTRICITY, PLOT_600, MeterResource.ELECTRICITY, queued, DaySet.ALL, null))
                .isEqualTo(new Failed(Reason.NO_TARIFF_FOR_ALL_DAYS, List.of(electricityTariff)));
        String item = NotChargedItemRecord.intervalKey(METER, "R2");

        // a version from 01.09 added
        Snapshot after = snapshot(tariff(electricityTariff, ELECTRICITY, perKwh(UUID.randomUUID(), "2026-09-01",
                "4.3200"), perKwh(october, "2026-10-01", "4.3200")));
        Plan plan = plan(ledger.rows(), expectedElectricity(after, queued, null), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION, Set.of(item));

        assertThat(describe(plan)).containsExactly("NEW 2026-09-21 0.00->280.80 280.80 in 2026-10");
        assertThat(plan.queue()).containsExactly(new QueueOutcome(item,
                ChargeIdentity.interval(ChargeSource.CONSUMPTION, METER, LocalDate.parse("2026-09-20"),
                        LocalDate.parse("2026-10-05")), Status.RESOLVED, null, List.of()));
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.TARIFF_VERSION);
    }

    @Test
    void fixedPercentageLossesFollowTheInterval() {
        Snapshot electricity = snapshot(electricityTariff());
        var r1 = point("2026-09-05", "8140", "R1");
        var r2 = point("2026-10-05", "8205", "R2");
        List<ChargeRecord> booked = ledger.electricity(electricity, interval(r1, r2), FIVE_PERCENT, OCT);
        assertThat(booked).extracting(ChargeRecord::getAmount).map(Money::toString)
                .containsExactly("280.80", "14.04");

        // 65 -> 75 kWh
        var corrected = point("2026-10-05", "8215", "R2-corrected");
        Plan plan = plan(ledger.rows(), expectedElectricity(electricity, interval(r1, corrected), FIVE_PERCENT),
                SEPTEMBER_CLOSED, TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "REVERSE_AND_RECHARGE 2026-09-06 280.80->324.00 43.20 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-09-06 14.04->16.20 2.16 in 2026-10");
        assertThat(plan.lines().get(1).recharge().calculation()).containsEntry("lossesPercent", "5.00")
                .containsEntry("consumption", "75.000");
    }

    // ---- requirement §3, run rows

    @Test
    void proportionalLossesRunOpenMonthIsReversedAsAWholeAndBookedAgain() {
        Snapshot electricity = snapshot(electricityTariff());
        Map<UUID, PlotFacts> plots = Map.of(PLOT, PLOT_600, PLOT_2, facts(PLOT_2, "600"));
        LocalDate from = LocalDate.parse("2026-10-01");
        LocalDate to = LocalDate.parse("2026-10-20");
        UUID run = UUID.randomUUID();
        ledger.lossesRun(lossesPlan(from, to, electricity, plots, Map.of(PLOT, "6", PLOT_2, "4")), run, OCT);

        // a reading of quarter A corrected: plot 1's share 6 -> 7.5 kWh, plot 2's stays
        List<Expected> expected = ExpectedCharges.ofLossesRun(
                lossesPlan(from, to, electricity, plots, Map.of(PLOT, "7.5", PLOT_2, "4")), run, ELECTRICITY);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "REVERSE_AND_RECHARGE 2026-10-01 25.92->32.40 6.48 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-10-01 17.28->17.28 0.00 in 2026-10");
        assertThat(plan.lines()).allSatisfy(line -> {
            assertThat(line.runId()).isEqualTo(run);
            assertThat(line.reasons()).contains(RecalculationChangeReason.RUN);
        });
        assertThat(plan.runs()).containsExactly(new RunOutcome(run, RunAction.REBOOK));
    }

    @Test
    void proportionalLossesRunClosedMonthGetsPerPlotAdjustments() {
        Snapshot electricity = snapshot(electricityTariff());
        Map<UUID, PlotFacts> plots = Map.of(PLOT, PLOT_600, PLOT_2, facts(PLOT_2, "600"));
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-30");
        UUID run = UUID.randomUUID();
        ledger.lossesRun(lossesPlan(from, to, electricity, plots, Map.of(PLOT, "6", PLOT_2, "4")), run, SEP);

        List<Expected> expected = ExpectedCharges.ofLossesRun(
                lossesPlan(from, to, electricity, plots, Map.of(PLOT, "7.5", PLOT_2, "4")), run, ELECTRICITY);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly("ADJUST 2026-09-01 25.92->32.40 6.48 in 2026-10");
        assertThat(plan.lines().getFirst().plotId()).isEqualTo(PLOT);
        assertThat(plan.runs()).containsExactly(new RunOutcome(run, RunAction.PER_LINE));
    }

    // ---- requirement §3, manual and repeated rows

    @Test
    void manualAreaCorrected600To700() {
        Snapshot membership = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ledger.membership(membership, PLOT_600, SEP, SEP);
        ledger.membership(membership, PLOT_600, OCT, OCT);

        Plan plan = plan(ledger.rows(), expectedMembership(membership, facts(PLOT, "700"), SEP, OCT),
                SEPTEMBER_CLOSED, TODAY, Cause.MANUAL);

        assertThat(describe(plan)).containsExactly(
                "ADJUST 2026-09-01 25.00->29.17 4.17 in 2026-10",
                "REVERSE_AND_RECHARGE 2026-10-01 25.00->29.17 4.17 in 2026-10");
        assertThat(plan.lines()).allSatisfy(line -> assertThat(line.reasons())
                .containsExactly(RecalculationChangeReason.AREA));
    }

    @Test
    void alreadyAdjustedChargeCorrectedAgain() {
        Snapshot sixty = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "60.0000")));
        ChargeRecord september = ledger.membership(sixty, PLOT_600, SEP, SEP);
        ledger.adjust(september, "-5.00", OCT);

        Snapshot forty = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "40.0000")));
        Plan plan = plan(ledger.rows(), expectedMembership(forty, PLOT_600, SEP), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(describe(plan)).containsExactly("ADJUST 2026-09-01 25.00->20.00 -5.00 in 2026-10");
        Line line = plan.lines().getFirst();
        assertThat(line.booked()).hasToString("30.00");
        assertThat(line.adjustments()).hasToString("-5.00");
    }

    @Test
    void runTwiceBooksNothingTheSecondTime() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "60.0000")));
        ChargeRecord september = ledger.membership(before, PLOT_600, SEP, SEP);
        ChargeRecord october = ledger.membership(before, PLOT_600, OCT, OCT);
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "50.0000")));
        assertThat(plan(ledger.rows(), expectedMembership(after, PLOT_600, SEP, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION).lines()).hasSize(2);

        // what the first booking wrote: the adjustment, the reversal and the new charge under a revision key
        ledger.adjust(september, "-5.00", OCT);
        ledger.reverseByRecalculation(october, OCT);
        ledger.membership(after, PLOT_600, OCT, OCT, "#r" + UUID.randomUUID());
        Plan again = plan(ledger.rows(), expectedMembership(after, PLOT_600, SEP, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(again.isEmpty()).isTrue();
        assertThat(again.runs()).isEmpty();
        assertThat(again.queue()).isEmpty();
        assertThat(again.total()).isEqualTo(Money.ZERO);
    }

    // ---- the rest of the rules

    @Test
    void aChargeReversedByHandStaysAsItIsAndOpeningBalancesAreNeverInScope() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ChargeRecord october = ledger.membership(before, PLOT_600, OCT, OCT);
        ledger.reverseByHand(october, OCT);
        ledger.add(ChargeRecord.opening(TENANT, PLOT, MEMBERSHIP, UUID.randomUUID(), Money.parse("100.00"),
                LocalDate.parse("2026-08-31"), null, null, Map.of(), null, NOW), SEP);

        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "60.0000")));
        Plan plan = plan(ledger.rows(), expectedMembership(after, PLOT_600, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION);

        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void aChargeReversedByARecalculationIsChargedAgainAsNew() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ChargeRecord october = ledger.membership(before, PLOT_600, OCT, OCT);
        ledger.reverseByRecalculation(october, OCT);

        Plan plan = plan(ledger.rows(), expectedMembership(before, PLOT_600, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.MANUAL);

        assertThat(describe(plan)).containsExactly("NEW 2026-10-01 0.00->25.00 25.00 in 2026-10");
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.TARIFF_SCOPE);
    }

    @Test
    void aChargeThatCannotBeCalculatedNowGoesAndIsQueued() {
        Snapshot electricity = snapshot(electricityTariff());
        MeterInterval interval = interval(point("2026-09-05", "8140", "R1"), point("2026-10-05", "8205", "R2"));
        ledger.electricity(electricity, interval, null, OCT);
        String item = NotChargedItemRecord.intervalKey(METER, "R2");
        String gone = NotChargedItemRecord.intervalKey(METER, "R0");

        // the version moved to 01.10: the interval is no longer covered on every day
        UUID tariff = electricity.tariffs().getFirst().id();
        Snapshot after = snapshot(tariff(tariff, ELECTRICITY, perKwh(UUID.randomUUID(), "2026-10-01", "4.3200")));
        Plan plan = plan(ledger.rows(), expectedElectricity(after, interval, null), SEPTEMBER_CLOSED, TODAY,
                Cause.TARIFF_VERSION, Set.of(gone));

        assertThat(describe(plan)).containsExactly("REVERSE 2026-09-06 280.80->0.00 -280.80 in 2026-10");
        Line line = plan.lines().getFirst();
        assertThat(line.notCharged()).isEqualTo(Reason.NO_TARIFF_FOR_ALL_DAYS);
        assertThat(line.reasons()).containsExactly(RecalculationChangeReason.NOT_CHARGED);
        assertThat(plan.queue()).containsExactly(
                new QueueOutcome(item, ChargeIdentity.interval(ChargeSource.CONSUMPTION, METER,
                        LocalDate.parse("2026-09-05"), LocalDate.parse("2026-10-05")), Status.OPEN,
                        Reason.NO_TARIFF_FOR_ALL_DAYS, List.of(tariff)),
                new QueueOutcome(gone, null, Status.OBSOLETE, null, List.of()));
    }

    @Test
    void aConflictOfTariffsReversesTheChargeAndQueuesTheMonth() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ledger.membership(before, PLOT_600, OCT, OCT);
        TariffEntry second = membership(UUID.randomUUID(), perSotka(UUID.randomUUID(), "2026-01-01", "40.0000"));

        Snapshot after = snapshot(before.tariffs().getFirst(), second);
        Plan plan = plan(ledger.rows(), expectedMembership(after, PLOT_600, OCT), SEPTEMBER_CLOSED, TODAY,
                Cause.MANUAL);

        assertThat(describe(plan)).containsExactly("REVERSE 2026-10-01 25.00->0.00 -25.00 in 2026-10");
        assertThat(plan.lines().getFirst().notCharged()).isEqualTo(Reason.CONFLICTING_TARIFFS);
        assertThat(plan.queue()).singleElement().satisfies(item -> {
            assertThat(item.itemKey()).isEqualTo(NotChargedItemRecord.tariffMonthKey(PLOT, MEMBERSHIP, OCT));
            assertThat(item.status()).isEqualTo(Status.OPEN);
            assertThat(item.reason()).isEqualTo(Reason.CONFLICTING_TARIFFS);
            assertThat(item.tariffIds()).containsExactlyInAnyOrder(tariffId, second.id());
        });
    }

    @Test
    void anOpenRunWhoseLinesAllGoIsReversedAndAnUnchangedRunStays() {
        Snapshot electricity = snapshot(electricityTariff());
        Map<UUID, PlotFacts> plots = Map.of(PLOT, PLOT_600);
        LocalDate from = LocalDate.parse("2026-10-01");
        LocalDate to = LocalDate.parse("2026-10-20");
        UUID gone = UUID.randomUUID();
        UUID same = UUID.randomUUID();
        LossesPlanning.Plan booked = lossesPlan(from, to, electricity, plots, Map.of(PLOT, "6"));
        ledger.lossesRun(booked, gone, OCT);
        ledger.lossesRun(booked, same, OCT);

        Plan plan = plan(ledger.rows(), ExpectedCharges.ofLossesRun(booked, same, ELECTRICITY), SEPTEMBER_CLOSED,
                TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly("REVERSE 2026-10-01 25.92->0.00 -25.92 in 2026-10");
        assertThat(plan.lines().getFirst().runId()).isEqualTo(gone);
        assertThat(plan.runs()).containsExactly(new RunOutcome(gone, RunAction.REVERSE));
    }

    @Test
    void anOpenRunBookedAgainLeavesAHandReversedLineAndChargesAPlotNewToIt() {
        Snapshot electricity = snapshot(electricityTariff());
        UUID plot3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Map<UUID, PlotFacts> plots = Map.of(PLOT, PLOT_600, PLOT_2, facts(PLOT_2, "600"), plot3, facts(plot3, "600"));
        LocalDate from = LocalDate.parse("2026-10-01");
        LocalDate to = LocalDate.parse("2026-10-20");
        UUID run = UUID.randomUUID();
        List<ChargeRecord> booked = ledger.lossesRun(lossesPlan(from, to, electricity, plots,
                Map.of(PLOT, "6", PLOT_2, "4")), run, OCT);
        ledger.reverseByHand(booked.stream().filter(charge -> charge.getPlotId().equals(PLOT)).findFirst()
                .orElseThrow(), OCT);

        List<Expected> expected = ExpectedCharges.ofLossesRun(lossesPlan(from, to, electricity, plots,
                Map.of(PLOT, "6", PLOT_2, "5", plot3, "2")), run, ELECTRICITY);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.READING);

        assertThat(describe(plan)).containsExactly(
                "REVERSE_AND_RECHARGE 2026-10-01 17.28->21.60 4.32 in 2026-10",
                "NEW 2026-10-01 0.00->8.64 8.64 in 2026-10");
        assertThat(plan.lines()).extracting(Line::plotId).containsExactly(PLOT_2, plot3);
        assertThat(plan.runs()).containsExactly(new RunOutcome(run, RunAction.REBOOK));
    }

    @Test
    void aPlotNewToAClosedRunGetsANewChargeAndAPlotThatCannotBePricedIsNotCharged() {
        Snapshot electricity = snapshot(electricityTariff());
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-30");
        UUID run = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        ledger.lossesRun(lossesPlan(from, to, electricity, Map.of(PLOT, PLOT_600), Map.of(PLOT, "6")), run, SEP);

        // plot 2 now shares the node too; a third plot has no facts, so it is outside every scope
        List<Expected> expected = ExpectedCharges.ofLossesRun(lossesPlan(from, to, electricity,
                Map.of(PLOT, PLOT_600, PLOT_2, facts(PLOT_2, "600")), Map.of(PLOT, "6", PLOT_2, "4", unknown, "1")),
                run, ELECTRICITY);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.MANUAL);

        assertThat(describe(plan)).containsExactly("NEW 2026-09-01 0.00->17.28 17.28 in 2026-10");
        assertThat(plan.lines().getFirst().runId()).isEqualTo(run);
        assertThat(expected).filteredOn(e -> e.plotId().equals(unknown)).singleElement()
                .satisfies(e -> assertThat(e.result()).isEqualTo(
                        new ExpectedCharges.NotCharged(Reason.OUTSIDE_SCOPE, List.of())));
        assertThat(plan.runs()).containsExactly(new RunOutcome(run, RunAction.PER_LINE));
    }

    @Test
    void aMeterMovedToAnotherPlotIsReversedThereAndChargedHere() {
        Snapshot electricity = snapshot(electricityTariff());
        MeterInterval interval = interval(point("2026-09-05", "8140", "R1"), point("2026-10-05", "8205", "R2"));
        ledger.electricity(electricity, interval, null, OCT);

        List<Expected> expected = ExpectedCharges.ofInterval(planInterval(electricity, ELECTRICITY,
                facts(PLOT_2, "600"), MeterResource.ELECTRICITY, interval, DaySet.ALL, null), interval, PLOT_2,
                ELECTRICITY);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.MANUAL);

        assertThat(describe(plan)).containsExactly(
                "NEW 2026-09-06 0.00->280.80 280.80 in 2026-10",
                "REVERSE 2026-09-06 280.80->0.00 -280.80 in 2026-10");
        assertThat(plan.lines()).extracting(Line::plotId).containsExactly(PLOT_2, PLOT);
        assertThat(plan.lines()).allSatisfy(line -> assertThat(line.reasons())
                .containsExactly(RecalculationChangeReason.METER_TREE));
    }

    @Test
    void aWaterIntervalChargedForOtherConnectionDaysNamesTheWaterConnection() {
        UUID water = UUID.randomUUID();
        Snapshot snapshot = snapshot(tariff(water, WATER, perM3(UUID.randomUUID(), "2026-01-01", "20.0000")));
        MeterInterval interval = interval(point("2026-09-05", "100", "R1"), point("2026-10-05", "130", "R2"));
        var charges = (ReadingChargePlanning.NewCharges) planInterval(snapshot, WATER, PLOT_600, MeterResource.WATER,
                interval, DaySet.ALL, null);
        ledger.reading(charges.consumption(), OCT);

        // connected only from 21.09: 15 of 30 days
        List<Expected> expected = ExpectedCharges.ofInterval(planInterval(snapshot, WATER, PLOT_600,
                MeterResource.WATER, interval, DaySet.of(LocalDate.parse("2026-09-21"), LocalDate.MAX), null),
                interval, PLOT, WATER);
        Plan plan = plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.MANUAL);

        assertThat(describe(plan)).containsExactly("REVERSE_AND_RECHARGE 2026-09-06 600.00->300.00 -300.00 in 2026-10");
        assertThat(plan.lines().getFirst().reasons()).containsExactly(RecalculationChangeReason.WATER_CONNECTION);
    }

    @Test
    void theOrderOfTheInputDoesNotMatter() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "60.0000")));
        ledger.membership(before, PLOT_600, SEP, SEP);
        ledger.membership(before, PLOT_600, OCT, OCT);
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-09-01", "50.0000")));
        List<Expected> expected = expectedMembership(after, PLOT_600, SEP, OCT);
        List<BookedCharge> rows = new ArrayList<>(ledger.rows());
        Collections.reverse(rows);
        List<Expected> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);

        assertThat(plan(rows, reversed, SEPTEMBER_CLOSED, TODAY, Cause.TARIFF_VERSION)).isEqualTo(
                plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.TARIFF_VERSION));
    }

    @Test
    void aMonthWithoutARowIsClosedBeforeTheLastClosedMonthAndOpenAfterIt() {
        List<MonthState> months = List.of(new MonthState(YearMonth.of(2026, 7), false),
                new MonthState(SEP, true));

        assertThat(RecalculationPlanner.closed(YearMonth.of(2026, 7), months)).isFalse();
        assertThat(RecalculationPlanner.closed(YearMonth.of(2026, 8), months)).isTrue();
        assertThat(RecalculationPlanner.closed(SEP, months)).isTrue();
        assertThat(RecalculationPlanner.closed(OCT, months)).isFalse();
        assertThat(RecalculationPlanner.closed(OCT, List.of())).isFalse();
    }

    @Test
    void theGuards() {
        Snapshot membership = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        List<Expected> expected = expectedMembership(membership, PLOT_600, OCT);
        List<Expected> twice = new ArrayList<>(expected);
        twice.addAll(expected);
        assertThatThrownBy(() -> plan(List.of(), twice, SEPTEMBER_CLOSED, TODAY, Cause.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);

        // the ledger never has two live charges of one identity
        ledger.membership(membership, PLOT_600, OCT, OCT);
        ledger.membership(membership, PLOT_600, OCT, OCT, "#r" + UUID.randomUUID());
        assertThatThrownBy(() -> plan(ledger.rows(), expected, SEPTEMBER_CLOSED, TODAY, Cause.MANUAL))
                .isInstanceOf(IllegalStateException.class);

        // a recalculation plans with a predicate that ignores the ledger
        assertThatThrownBy(() -> ExpectedCharges.ofTariffMonth(
                TariffMonthPlanning.plan(membership, MEMBERSHIP, PLOT_600, OCT, DaySet.ALL, key -> true), PLOT,
                MEMBERSHIP, OCT)).isInstanceOf(IllegalArgumentException.class);
        MeterInterval interval = interval(point("2026-09-05", "8140", "R1"), point("2026-10-05", "8205", "R2"));
        assertThatThrownBy(() -> ExpectedCharges.ofInterval(ReadingChargePlanning.plan(snapshot(electricityTariff()),
                ELECTRICITY, PLOT_600, MeterResource.ELECTRICITY, interval, DaySet.ALL, null, key -> true), interval,
                PLOT, ELECTRICITY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExpectedCharges.ofInterval(ReadingChargePlanning.plan(snapshot(electricityTariff()),
                ELECTRICITY, PLOT_600, MeterResource.WATER, interval, DaySet.NONE, null, key -> false), interval,
                PLOT, ELECTRICITY)).isEmpty();
        assertThatThrownBy(() -> new ExpectedCharges.Charge(Money.ZERO, LocalDate.MIN, LocalDate.MIN, LocalDate.MIN,
                Map.of(), new ExpectedCharges.OfTariff(null))).isInstanceOf(IllegalArgumentException.class);
    }
}
