package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.meters.internal.ReadingValidator.MeterSpan;
import app.nadel.meters.internal.ReadingValidator.Neighbours;
import app.nadel.meters.internal.ReadingValidator.Violation;
import app.nadel.meters.internal.ReadingValidator.Warning;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rules a reading follows before it counts (spec 010, section 2.4) and the unusual-consumption warning, without a
 * database: date not in the future, within the working period, the value between the neighbouring points.
 */
class ReadingValidatorTests {

    private static final LocalDate TODAY = date("2026-10-21");

    /** Installed 01.06 with 8 000, still active. */
    private static final MeterSpan ACTIVE = new MeterSpan(date("2026-06-01"), value("8000"), null, null);

    /** Installed 01.06 with 8 000, removed 15.10 with 8 420. */
    private static final MeterSpan REMOVED = new MeterSpan(date("2026-06-01"), value("8000"), date("2026-10-15"),
            value("8420"));

    // ---- dates

    @Test
    void aReadingIsNotDatedInTheFuture() {
        assertThat(check(ACTIVE, List.of(), "2026-10-22", "8100")).contains(
                new Violation("READING_DATE_IN_FUTURE", "takenOn", Map.of("today", "2026-10-21")));
        assertThat(check(ACTIVE, List.of(), "2026-10-21", "8100")).isEmpty();
    }

    @Test
    void aReadingIsDatedWithinTheWorkingPeriodOfItsMeter() {
        assertThat(check(ACTIVE, List.of(), "2026-05-31", "8100")).contains(
                new Violation("READING_DATE_OUTSIDE_METER", "takenOn", Map.of("installedOn", "2026-06-01")));
        assertThat(check(REMOVED, List.of(), "2026-10-16", "8400")).contains(
                new Violation("READING_DATE_OUTSIDE_METER", "takenOn",
                        Map.of("installedOn", "2026-06-01", "removedOn", "2026-10-15")));
        assertThat(check(ACTIVE, List.of(), "2026-06-01", "8000")).isEmpty();
        assertThat(check(REMOVED, List.of(), "2026-10-15", "8420")).isEmpty();
    }

    @Test
    void theFutureIsCheckedBeforeTheWorkingPeriod() {
        MeterSpan removedLater = new MeterSpan(date("2026-06-01"), value("0"), date("2026-10-15"), value("10"));

        assertThat(check(removedLater, List.of(), "2026-11-01", "5")).map(Violation::code)
                .contains("READING_DATE_IN_FUTURE");
    }

    // ---- order

    @Test
    void aReadingLowerThanThePreviousOneIsRefused() {
        // spec 2.5 "Lower reading": 03.09: 8 140, 05.10 entered 8 100
        assertThat(check(ACTIVE, List.of(reading("2026-09-03", "8140")), "2026-10-05", "8100")).contains(
                new Violation("READING_OUT_OF_ORDER", "value",
                        Map.of("previousTakenOn", "2026-09-03", "previousValue", "8140.000")));
    }

    @Test
    void aReadingHigherThanTheNextOneIsRefused() {
        List<Reading> counted = List.of(reading("2026-09-03", "8140"), reading("2026-10-05", "8205"));

        assertThat(check(ACTIVE, counted, "2026-09-20", "8210")).contains(new Violation("READING_OUT_OF_ORDER",
                "value", Map.of("nextTakenOn", "2026-10-05", "nextValue", "8205.000")));
    }

    @Test
    void aReadingBetweenTwoOthersIsAcceptedWhenItFitsBetweenThem() {
        // spec 2.5 "Reading between two others": 03.09: 8 140, 05.10: 8 205, then 20.09: 8 170
        List<Reading> counted = List.of(reading("2026-10-05", "8205"), reading("2026-09-03", "8140"));

        assertThat(check(ACTIVE, counted, "2026-09-20", "8170")).isEmpty();
        assertThat(check(ACTIVE, counted, "2026-09-20", "8140")).isEmpty();
        assertThat(check(ACTIVE, counted, "2026-09-20", "8205")).isEmpty();
    }

    @Test
    void theInitialAndTheFinalReadingsAreNeighboursToo() {
        assertThat(check(ACTIVE, List.of(), "2026-07-01", "7999.999")).map(Violation::params)
                .contains(Map.of("previousTakenOn", "2026-06-01", "previousValue", "8000.000"));
        assertThat(check(REMOVED, List.of(), "2026-10-01", "8420.001")).map(Violation::params)
                .contains(Map.of("nextTakenOn", "2026-10-15", "nextValue", "8420.000"));
    }

    @Test
    void aPointOnTheSameDateIsNoNeighbour() {
        // a reading on the installation date takes the place of the initial reading
        assertThat(check(ACTIVE, List.of(), "2026-06-01", "7990")).isEmpty();
        // a counted reading on the date is being replaced, so it does not bound the new value
        assertThat(check(ACTIVE, List.of(reading("2026-10-05", "8205")), "2026-10-05", "8250")).isEmpty();
    }

    // ---- neighbours and the use span

    @Test
    void theNeighboursAreTheNearestPointsOnBothSides() {
        List<Reading> counted = List.of(reading("2026-09-03", "8140"), reading("2026-10-05", "8205"));

        Neighbours between = ReadingValidator.neighbours(ACTIVE, counted, date("2026-09-20"));
        assertThat(between).isEqualTo(new Neighbours(reading("2026-09-03", "8140"), reading("2026-10-05", "8205")));
        // the days a change on 20.09 reaches: from the day after the previous point to the day before the next one
        assertThat(between.spanFrom(date("2026-09-20"))).isEqualTo("2026-09-04");
        assertThat(between.spanTo(date("2026-09-20"))).isEqualTo("2026-10-04");

        Neighbours last = ReadingValidator.neighbours(ACTIVE, counted, date("2026-10-20"));
        assertThat(last.next()).isNull();
        assertThat(last.spanTo(date("2026-10-20"))).isEqualTo("2026-10-20");

        Neighbours first = ReadingValidator.neighbours(ACTIVE, counted, date("2026-06-01"));
        assertThat(first.previous()).isNull();
        assertThat(first.spanFrom(date("2026-06-01"))).isEqualTo("2026-06-01");
    }

    // ---- intervals and warnings

    @Test
    void theIntervalOfAReadingStartsAtThePointBeforeIt() {
        List<Reading> counted = List.of(reading("2026-09-03", "8140"), reading("2026-10-05", "8205"));

        assertThat(ReadingValidator.intervalEndingAt(ACTIVE, counted, date("2026-09-20"), value("8170")))
                .contains(new Interval(date("2026-09-03"), date("2026-09-20"), Quantity.of(value("30"))));
        assertThat(ReadingValidator.intervalEndingAt(ACTIVE, counted, date("2026-10-05"), value("8205")))
                .contains(new Interval(date("2026-09-03"), date("2026-10-05"), Quantity.of(value("65"))));
        assertThat(ReadingValidator.intervalEndingAt(ACTIVE, List.of(), date("2026-06-01"), value("8000")))
                .isEmpty();
    }

    @Test
    void aReadingFarAboveTheUsualDailyConsumptionIsWarnedAbout() {
        // spec 2.5 "Unusual consumption": usual 2 kWh a day; 10 days with 400 kWh
        MeterSpan meter = new MeterSpan(date("2026-07-01"), value("1000"), null, null);
        List<Reading> counted = List.of(reading("2026-08-01", "1062"));

        assertThat(ReadingValidator.warnings(meter, counted, date("2026-08-11"), value("1462")))
                .containsExactly(Warning.UNUSUAL_CONSUMPTION);
        assertThat(ReadingValidator.warnings(meter, counted, date("2026-08-11"), value("1082"))).isEmpty();
        // exactly three times the usual consumption is not unusual yet
        assertThat(ReadingValidator.warnings(meter, counted, date("2026-08-11"), value("1122"))).isEmpty();
        assertThat(ReadingValidator.warnings(meter, counted, date("2026-08-11"), value("1122.001")))
                .containsExactly(Warning.UNUSUAL_CONSUMPTION);
    }

    @Test
    void thereIsNoWarningWithoutAnEarlierInterval() {
        MeterSpan meter = new MeterSpan(date("2026-07-01"), value("1000"), null, null);

        assertThat(ReadingValidator.warnings(meter, List.of(), date("2026-07-11"), value("5000"))).isEmpty();
        assertThat(ReadingValidator.warnings(meter, List.of(), date("2026-07-01"), value("1000"))).isEmpty();
    }

    @Test
    void anIntervalOlderThanHalfAYearIsNotTheUsualConsumption() {
        MeterSpan meter = new MeterSpan(date("2025-01-01"), value("0"), null, null);
        List<Reading> counted = List.of(reading("2025-02-01", "31"));

        assertThat(ReadingValidator.warnings(meter, counted, date("2026-10-01"), value("10000"))).isEmpty();
    }

    @Test
    void aReadingInsertedBetweenTwoOthersIsComparedWithTheIntervalsBeforeIt() {
        MeterSpan meter = new MeterSpan(date("2026-07-01"), value("1000"), null, null);
        List<Reading> counted = List.of(reading("2026-08-01", "1062"), reading("2026-09-01", "1600"));

        assertThat(ReadingValidator.warnings(meter, counted, date("2026-08-11"), value("1500")))
                .containsExactly(Warning.UNUSUAL_CONSUMPTION);
    }

    // ---- helpers

    private static Optional<Violation> check(MeterSpan meter, List<Reading> counted, String takenOn, String value) {
        return ReadingValidator.check(meter, counted, TODAY, date(takenOn), value(value));
    }

    private static Reading reading(String takenOn, String value) {
        return new Reading(date(takenOn), value(value));
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static MeterValue value(String text) {
        return MeterValue.parse(text);
    }
}
