package app.nadel.meters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.ParentPeriod;
import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.ConsumptionCalculator.Slice;
import app.nadel.meters.ConsumptionCalculator.ValueAt;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Intervals, the value at a date, range consumption over one meter and a chain of meters, the split by parent and the
 * unusual-consumption check: every row of the worked examples of functional spec 2.5 that is a pure calculation, with its
 * exact expected number, and the edges around them. The rows that are decisions about input (a lower reading is refused,
 * a member's pending reading is ignored, the same date twice supersedes, the default date, a future date) belong to the
 * reading commands; here the calculator receives only the readings that count.
 */
class ConsumptionCalculatorTests {

    private static final UUID QUARTER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID QUARTER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    private static MeterValue v(String value) {
        return MeterValue.parse(value);
    }

    private static Quantity q(String value) {
        return Quantity.of(v(value));
    }

    private static Quantity fraction(long numerator, long denominator) {
        return Quantity.ofFraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    private static Reading reading(String date, String value) {
        return new Reading(d(date), v(value));
    }

    /** A meter installed long before the range, never removed. */
    private static MeterHistory meter(Reading... readings) {
        return new MeterHistory(d("2026-01-01"), v("0"), null, null, List.of(readings));
    }

    private static MeterHistory meter(String installedOn, String initial, Reading... readings) {
        return new MeterHistory(d(installedOn), v(initial), null, null, List.of(readings));
    }

    private static MeterHistory removed(String installedOn, String initial, String removedOn, String finalValue,
            Reading... readings) {
        return new MeterHistory(d(installedOn), v(initial), d(removedOn), v(finalValue), List.of(readings));
    }

    private static RangeConsumption.Known known(RangeConsumption consumption) {
        assertThat(consumption).isInstanceOf(RangeConsumption.Known.class);
        return (RangeConsumption.Known) consumption;
    }

    private static Interval interval(String from, String to, String consumption) {
        return new Interval(d(from), d(to), q(consumption));
    }

    // ---- the worked examples (functional spec 2.5)

    @Test
    void normalInterval() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"), reading("2026-10-05", "8205"));

        Interval interval = ConsumptionCalculator.intervals(meter).getLast();

        assertThat(interval.from()).isEqualTo(d("2026-09-03"));
        assertThat(interval.to()).isEqualTo(d("2026-10-05"));
        assertThat(interval.consumption()).isEqualTo(q("65"));
        assertThat(interval.days()).isEqualTo(32);
        assertThat(known(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-03"), d("2026-10-05"))))
                .isEqualTo(new RangeConsumption.Known(q("65"), false));
    }

    @Test
    void longInterval() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"), reading("2027-01-28", "8552"));

        Interval interval = ConsumptionCalculator.intervals(meter).getLast();

        assertThat(interval.consumption()).isEqualTo(q("412"));
        assertThat(interval.from()).isEqualTo(d("2026-09-03"));
        assertThat(interval.to()).isEqualTo(d("2027-01-28"));
        // nothing in between: only the initial point and the two readings exist
        assertThat(ConsumptionCalculator.intervals(meter)).hasSize(2);
    }

    @Test
    void replacementAddsUpBothMeters() {
        MeterHistory old = removed("2026-01-01", "7000", "2026-09-20", "8205", reading("2026-09-03", "8140"));
        MeterHistory replacement = meter("2026-09-20", "3", reading("2026-10-05", "69"));

        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(List.of(old, replacement),
                d("2026-09-03"), d("2026-10-05"));

        // (8205 - 8140) + (69 - 3) = 65 + 66
        assertThat(consumption).isEqualTo(new RangeConsumption.Known(q("131"), false));
        assertThat(known(ConsumptionCalculator.rangeConsumption(old, d("2026-09-03"), d("2026-10-05"))).consumption())
                .isEqualTo(q("65"));
        assertThat(known(ConsumptionCalculator.rangeConsumption(replacement, d("2026-09-03"), d("2026-10-05")))
                .consumption()).isEqualTo(q("66"));
    }

    @Test
    void counterWrapAroundIsAReplacementOfTheMeterByItself() {
        MeterHistory before = removed("2026-01-01", "90000", "2026-09-15", "99999", reading("2026-09-03", "99950"));
        MeterHistory after = meter("2026-09-15", "0", reading("2026-10-05", "12"));

        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(List.of(before, after),
                d("2026-09-03"), d("2026-10-05"));

        // (99 999 - 99 950) + (12 - 0) = 49 + 12
        assertThat(consumption).isEqualTo(new RangeConsumption.Known(q("61"), false));
    }

    @Test
    void aReadingBetweenTwoOthersSplitsTheIntervalInTwo() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"), reading("2026-09-20", "8170"),
                reading("2026-10-05", "8205"));

        List<Interval> intervals = ConsumptionCalculator.intervals(meter);

        assertThat(intervals).hasSize(3); // the first one runs from the installation to 03.09
        assertThat(intervals.get(1)).isEqualTo(interval("2026-09-03", "2026-09-20", "30"));
        assertThat(intervals.get(2)).isEqualTo(interval("2026-09-20", "2026-10-05", "35"));
    }

    @Test
    void aMeterInstalledRecently() {
        MeterHistory meter = meter("2026-10-10", "0", reading("2026-10-31", "120"));

        assertThat(ConsumptionCalculator.intervals(meter)).containsExactly(interval("2026-10-10", "2026-10-31", "120"));
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-10-10"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Known(q("120"), false));
    }

    @Test
    void aRangeWithCalculationInterpolatesByDays() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));

        ValueAt at = ConsumptionCalculator.valueAt(meter, d("2026-10-16"));
        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(meter, d("2026-10-01"), d("2026-10-16"));

        // 1 000 + 300 x 15 / 30
        assertThat(at).isEqualTo(new ValueAt.Calculated(q("1150")));
        assertThat(((ValueAt.Calculated) at).value().toMeterValue().toString()).isEqualTo("1150.000");
        assertThat(consumption).isEqualTo(new RangeConsumption.Known(q("150"), true));
    }

    @Test
    void aRangeWithoutALaterReadingIsUnknown() {
        MeterHistory meter = meter(reading("2026-10-05", "8205"));

        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(meter, d("2026-10-01"), d("2026-10-31"));

        assertThat(consumption).isEqualTo(new RangeConsumption.Unknown(d("2026-10-05")));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-31")))
                .isEqualTo(new ValueAt.Unknown(d("2026-10-05")));
    }

    @Test
    void theSameDateTwiceIsDecidedBeforeTheCalculator() {
        // 05.10: 8 205 is superseded by 05.10: 8 250 when the reading is entered; the calculator is given 8 250 only
        MeterHistory meter = meter(reading("2026-09-03", "8140"), reading("2026-10-05", "8250"));

        assertThat(ConsumptionCalculator.intervals(meter).getLast().consumption()).isEqualTo(q("110"));
        // and it refuses two readings of one date, so a superseded one can never be counted by mistake
        assertThatThrownBy(() -> meter(reading("2026-10-05", "8205"), reading("2026-10-05", "8250")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unusualConsumptionIsMoreThanThreeTimesTheUsualDailyConsumption() {
        // usual: 2 kWh a day over the last 180 days
        List<Interval> earlier = List.of(interval("2026-08-01", "2026-08-21", "40"),
                interval("2026-08-21", "2026-09-10", "40"));
        Interval candidate = interval("2026-09-10", "2026-09-20", "400");

        assertThat(ConsumptionCalculator.isUnusual(earlier, candidate)).isTrue();
        // 10 days at the usual 2 kWh a day, and at almost three times that, are fine
        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "20"))).isFalse();
        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "59.999"))).isFalse();
    }

    @Test
    void aPlotMovedToAnotherQuarterIsSplitAtTheMoveDate() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-15", "1140"), reading("2026-10-31", "1300"));
        List<ParentPeriod> periods = List.of(
                new ParentPeriod(QUARTER_B, d("2026-10-15"), null),
                new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-10-14")));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-01"),
                d("2026-10-31"));

        assertThat(slices).containsExactly(
                new Slice(QUARTER_A, d("2026-10-01"), d("2026-10-15"), new RangeConsumption.Known(q("140"), false)),
                new Slice(QUARTER_B, d("2026-10-15"), d("2026-10-31"), new RangeConsumption.Known(q("160"), false)));
        assertThat(total(slices)).isEqualTo(q("300"));
    }

    @Test
    void theSlicesOfAMovedPlotAddUpToTheWholeEvenWhenTheMoveDateIsInterpolated() {
        // no reading on the move date: its value is calculated, and both slices use that same value
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-10-14")),
                new ParentPeriod(QUARTER_B, d("2026-10-15"), null));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-01"),
                d("2026-10-31"));

        assertThat(slices).extracting(Slice::consumption).containsExactly(
                new RangeConsumption.Known(q("140"), true), new RangeConsumption.Known(q("160"), true));
        assertThat(total(slices)).isEqualTo(q("300"));
    }

    // ---- exact arithmetic: nothing is rounded on the way

    @Test
    void interpolatedValuesAreNotRoundedOnTheWay() {
        // 0 on 01.10 and 1 on 04.10: the value on 02.10 is 1/3 and on 03.10 is 2/3
        MeterHistory meter = meter("2026-10-01", "0", reading("2026-10-04", "1"));

        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-02"))).isEqualTo(new ValueAt.Calculated(fraction(1, 3)));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-03"))).isEqualTo(new ValueAt.Calculated(fraction(2, 3)));
        // the day between them is exactly a third: 0.667 - 0.333 = 0.334 would be wrong
        assertThat(known(ConsumptionCalculator.rangeConsumption(meter, d("2026-10-02"), d("2026-10-03"))).consumption())
                .isEqualTo(fraction(1, 3));
        // three one-day parts are exactly the whole
        Quantity parts = Quantity.ZERO;
        for (String[] day : new String[][] {{"2026-10-01", "2026-10-02"}, {"2026-10-02", "2026-10-03"},
                {"2026-10-03", "2026-10-04"}}) {
            parts = parts.plus(known(ConsumptionCalculator.rangeConsumption(meter, d(day[0]), d(day[1]))).consumption());
        }
        assertThat(parts).isEqualTo(q("1"));
    }

    @Test
    void aCalculatedValueIsRoundedOnlyWhenItIsShown() {
        MeterHistory meter = meter("2026-10-01", "0", reading("2026-10-04", "1"));

        ValueAt.Calculated second = (ValueAt.Calculated) ConsumptionCalculator.valueAt(meter, d("2026-10-02"));
        ValueAt.Calculated third = (ValueAt.Calculated) ConsumptionCalculator.valueAt(meter, d("2026-10-03"));

        assertThat(second.value().toMeterValue().toString()).isEqualTo("0.333");
        assertThat(third.value().toMeterValue().toString()).isEqualTo("0.667");
    }

    // ---- value at a date: both ends and outside

    @Test
    void theValueOnAPointIsTheRealValue() {
        MeterHistory meter = removed("2026-10-01", "1000", "2026-12-01", "1600", reading("2026-10-31", "1300"));

        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-01"))).isEqualTo(new ValueAt.Real(v("1000")));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-31"))).isEqualTo(new ValueAt.Real(v("1300")));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-12-01"))).isEqualTo(new ValueAt.Real(v("1600")));
    }

    @Test
    void interpolationNextToBothEndsOfAnInterval() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));

        // one day after the earlier point and one day before the later point: 1/30 and 29/30 of 300
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-02"))).isEqualTo(new ValueAt.Calculated(q("1010")));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-30"))).isEqualTo(new ValueAt.Calculated(q("1290")));
    }

    @Test
    void interpolationUsesTheNearestPointsOnBothSides() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-11", "1100"), reading("2026-10-31", "1500"));

        // between 11.10 (1 100) and 31.10 (1 500), not between the first and the last point
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-21"))).isEqualTo(new ValueAt.Calculated(q("1300")));
    }

    @Test
    void aValueOutsideTheWorkedDaysIsUnknown() {
        MeterHistory meter = removed("2026-10-01", "1000", "2026-12-01", "1600", reading("2026-10-31", "1300"));

        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-09-30"))).isEqualTo(new ValueAt.Unknown(null));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-12-02"))).isEqualTo(new ValueAt.Unknown(d("2026-12-01")));
    }

    @Test
    void theInitialReadingIsTheLastPointOfAMeterWithoutReadings() {
        MeterHistory meter = meter("2026-10-10", "0");

        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-20"))).isEqualTo(new ValueAt.Unknown(d("2026-10-10")));
        assertThat(ConsumptionCalculator.intervals(meter)).isEmpty();
    }

    @Test
    void aReadingOnTheInstallationOrRemovalDateTakesThePlaceOfThatPoint() {
        MeterHistory meter = removed("2026-10-01", "1000", "2026-12-01", "1600", reading("2026-10-01", "1005"),
                reading("2026-12-01", "1605"));

        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-10-01"))).isEqualTo(new ValueAt.Real(v("1005")));
        assertThat(ConsumptionCalculator.valueAt(meter, d("2026-12-01"))).isEqualTo(new ValueAt.Real(v("1605")));
        assertThat(ConsumptionCalculator.intervals(meter)).containsExactly(interval("2026-10-01", "2026-12-01", "600"));
    }

    @Test
    void intervalsRunUpToTheFinalReadingOfARemovedMeter() {
        MeterHistory meter = removed("2026-01-01", "7000", "2026-09-20", "8205", reading("2026-09-03", "8140"));

        assertThat(ConsumptionCalculator.intervals(meter)).containsExactly(
                interval("2026-01-01", "2026-09-03", "1140"), interval("2026-09-03", "2026-09-20", "65"));
    }

    // ---- range consumption: clipping, empty clips, chains

    @Test
    void aRangeIsClippedToTheInstallationAndRemovalDates() {
        MeterHistory meter = removed("2026-10-10", "100", "2026-11-20", "700", reading("2026-10-31", "400"));

        // from the 1st of September to the end of the year, but the meter worked from 10.10 to 20.11 only
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-01"), d("2026-12-31")))
                .isEqualTo(new RangeConsumption.Known(q("600"), false));
        // clipped at the start only, and at the end only
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-01"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Known(q("300"), false));
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-10-31"), d("2026-12-31")))
                .isEqualTo(new RangeConsumption.Known(q("300"), false));
    }

    @Test
    void aRangeOutsideTheWorkedDaysConsumedNothingAndIsKnown() {
        MeterHistory meter = removed("2026-10-10", "100", "2026-11-20", "700", reading("2026-10-31", "400"));

        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-01"), d("2026-10-01")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-12-01"), d("2026-12-31")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
        // touching the working period in one day only is no consumption either, even where nothing is known after it
        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-01"), d("2026-10-10")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
        assertThat(ConsumptionCalculator.rangeConsumption(meter("2026-10-10", "100"), d("2026-09-01"), d("2026-10-10")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
    }

    @Test
    void aSingleDayRangeConsumedNothing() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"), reading("2026-10-05", "8205"));

        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-09-20"), d("2026-09-20")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"));

        assertThatThrownBy(() -> ConsumptionCalculator.rangeConsumption(meter, d("2026-10-01"), d("2026-09-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsumptionCalculator.rangeConsumption(List.of(meter), d("2026-10-01"),
                d("2026-09-01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsumptionCalculator.splitByParent(List.of(meter), List.of(), d("2026-10-01"),
                d("2026-09-01"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRangeStartingAfterTheLastReadingIsUnknown() {
        MeterHistory meter = meter(reading("2026-09-03", "8140"));

        assertThat(ConsumptionCalculator.rangeConsumption(meter, d("2026-10-01"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Unknown(d("2026-09-03")));
    }

    @Test
    void anUnknownPartMakesAChainUnknownNotPartlyKnown() {
        MeterHistory old = removed("2026-01-01", "7000", "2026-09-20", "8205", reading("2026-09-03", "8140"));
        MeterHistory replacement = meter("2026-09-20", "3", reading("2026-10-05", "69"));

        // the new meter has no reading after 05.10, so the range to 31.10 is unknown although the old meter is known
        assertThat(ConsumptionCalculator.rangeConsumption(List.of(old, replacement), d("2026-09-03"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Unknown(d("2026-10-05")));
        // a range that ends where the replacement has a reading is known
        assertThat(ConsumptionCalculator.rangeConsumption(List.of(old, replacement), d("2026-09-03"), d("2026-10-05")))
                .isEqualTo(new RangeConsumption.Known(q("131"), false));
    }

    @Test
    void anUnknownChainReportsTheLatestMissingReadingDate() {
        MeterHistory first = meter(reading("2026-09-03", "100"));
        MeterHistory second = meter(reading("2026-10-05", "200"));

        assertThat(ConsumptionCalculator.rangeConsumption(List.of(first, second), d("2026-10-10"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Unknown(d("2026-10-05")));
        assertThat(ConsumptionCalculator.rangeConsumption(List.of(second, first), d("2026-10-10"), d("2026-10-31")))
                .isEqualTo(new RangeConsumption.Unknown(d("2026-10-05")));
    }

    @Test
    void aChainIsCalculatedWhenAnyPartIs() {
        MeterHistory old = removed("2026-01-01", "7000", "2026-09-20", "8205", reading("2026-09-03", "8140"));
        MeterHistory replacement = meter("2026-09-20", "3", reading("2026-10-05", "69"));

        // 03.09 and 28.09 (between 20.09: 3 and 05.10: 69, 8/15 of 66 = 35.2): the second part is interpolated
        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(List.of(old, replacement),
                d("2026-09-03"), d("2026-09-28"));

        assertThat(consumption).isEqualTo(new RangeConsumption.Known(q("65").plus(fraction(528, 15)), true));
    }

    @Test
    void anEmptyChainConsumedNothing() {
        assertThat(ConsumptionCalculator.rangeConsumption(List.<MeterHistory>of(), d("2026-09-01"), d("2026-09-30")))
                .isEqualTo(new RangeConsumption.Known(Quantity.ZERO, false));
    }

    // ---- split by parent: edges

    @Test
    void aPeriodOutsideTheRangeHasNoSlice() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-09-30")),
                new ParentPeriod(QUARTER_B, d("2026-10-01"), null));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-01"),
                d("2026-10-31"));

        assertThat(slices).extracting(Slice::parentMeterId).containsExactly(QUARTER_B);
        assertThat(slices.getFirst().consumption()).isEqualTo(new RangeConsumption.Known(q("300"), false));
    }

    @Test
    void aClosedPeriodEndingInsideTheRangeAndDaysWithoutAParentLeaveAGap() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-10-05"), d("2026-10-09")),
                new ParentPeriod(QUARTER_B, d("2026-10-20"), d("2026-10-24")));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-01"),
                d("2026-10-31"));

        // 05.10-10.10 (up to the day after the period's last day) and 20.10-25.10
        assertThat(slices).extracting(Slice::from, Slice::to).containsExactly(
                org.assertj.core.groups.Tuple.tuple(d("2026-10-05"), d("2026-10-10")),
                org.assertj.core.groups.Tuple.tuple(d("2026-10-20"), d("2026-10-25")));
        assertThat(slices).extracting(Slice::consumption).containsExactly(
                new RangeConsumption.Known(q("50"), true), new RangeConsumption.Known(q("50"), true));
    }

    @Test
    void aSliceBeyondTheLastReadingIsUnknownWhileTheEarlierOneIsKnown() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-15", "1140"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-10-14")),
                new ParentPeriod(QUARTER_B, d("2026-10-15"), null));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-01"),
                d("2026-10-31"));

        assertThat(slices).extracting(Slice::consumption).containsExactly(
                new RangeConsumption.Known(q("140"), false), new RangeConsumption.Unknown(d("2026-10-15")));
    }

    @Test
    void aSingleDayRangeHasOneZeroSliceUnderTheParentOfThatDay() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-10-14")),
                new ParentPeriod(QUARTER_B, d("2026-10-15"), null));

        assertThat(ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-20"), d("2026-10-20")))
                .containsExactly(new Slice(QUARTER_B, d("2026-10-20"), d("2026-10-20"),
                        new RangeConsumption.Known(Quantity.ZERO, false)));
        assertThat(ConsumptionCalculator.splitByParent(List.of(meter), periods, d("2026-10-14"), d("2026-10-14")))
                .extracting(Slice::parentMeterId).containsExactly(QUARTER_A);
        assertThat(ConsumptionCalculator.splitByParent(List.of(meter), List.of(), d("2026-10-20"), d("2026-10-20")))
                .isEmpty();
    }

    @Test
    void aMeterWithoutAParentHasNoSlices() {
        MeterHistory meter = meter("2026-10-01", "1000", reading("2026-10-31", "1300"));

        assertThat(ConsumptionCalculator.splitByParent(List.of(meter), List.of(), d("2026-10-01"), d("2026-10-31")))
                .isEmpty();
    }

    @Test
    void theSlicesOfAReplacedPlotSumTheChain() {
        MeterHistory old = removed("2026-01-01", "7000", "2026-10-20", "8205", reading("2026-10-01", "8140"));
        MeterHistory replacement = meter("2026-10-20", "3", reading("2026-10-31", "69"));
        List<ParentPeriod> periods = List.of(new ParentPeriod(QUARTER_A, d("2026-01-01"), d("2026-10-14")),
                new ParentPeriod(QUARTER_B, d("2026-10-15"), null));

        List<Slice> slices = ConsumptionCalculator.splitByParent(List.of(old, replacement), periods, d("2026-10-01"),
                d("2026-10-31"));

        // A: 01.10-15.10 on the old meter, 14/19 of 65; B: 15.10-20.10 on the old meter plus 20.10-31.10 on the new one
        assertThat(total(slices)).isEqualTo(q("131"));
        assertThat(slices).hasSize(2);
    }

    // ---- unusual consumption: edges

    @Test
    void exactlyThreeTimesTheUsualConsumptionIsNotUnusual() {
        List<Interval> earlier = List.of(interval("2026-08-01", "2026-09-10", "80")); // 2 kWh a day

        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "60"))).isFalse();
        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "60.001"))).isTrue();
    }

    @Test
    void withoutAnEarlierIntervalThereIsNoWarning() {
        assertThat(ConsumptionCalculator.isUnusual(List.of(), interval("2026-09-10", "2026-09-20", "100000")))
                .isFalse();
    }

    @Test
    void whenTheUsualConsumptionIsZeroThereIsNoWarning() {
        List<Interval> earlier = List.of(interval("2026-08-01", "2026-09-10", "0"));

        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "400"))).isFalse();
    }

    @Test
    void anIntervalOfNoDaysIsNeverUnusual() {
        List<Interval> earlier = List.of(interval("2026-08-01", "2026-09-10", "80"));

        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-10", "400"))).isFalse();
    }

    @Test
    void onlyIntervalsThatEndedInTheLastHundredEightyDaysCount() {
        Interval candidate = interval("2026-09-10", "2026-09-20", "400"); // window: 2026-03-24 (excluded) to 09-10
        Interval tooOld = interval("2026-02-01", "2026-03-24", "1"); // ends exactly 180 days before: outside
        Interval justInside = interval("2026-02-01", "2026-03-25", "1"); // 179 days before: inside
        Interval usual = interval("2026-08-01", "2026-09-10", "80");

        // the old interval is the only baseline of 1 kWh in 51 days, so 400 in 10 days is unusual either way; add a
        // heavy old interval to see which ones count
        Interval heavyOld = interval("2026-01-01", "2026-03-24", "100000");
        Interval heavyInside = interval("2026-01-01", "2026-03-25", "100000");

        assertThat(ConsumptionCalculator.isUnusual(List.of(tooOld, usual), candidate)).isTrue();
        assertThat(ConsumptionCalculator.isUnusual(List.of(justInside, usual), candidate)).isTrue();
        assertThat(ConsumptionCalculator.isUnusual(List.of(heavyOld, usual), candidate)).isTrue(); // heavy one ignored
        assertThat(ConsumptionCalculator.isUnusual(List.of(heavyInside, usual), candidate)).isFalse(); // counted
    }

    @Test
    void anIntervalThatEndsAfterTheNewOneStartsIsNotABaseline() {
        Interval candidate = interval("2026-09-10", "2026-09-20", "400");
        Interval later = interval("2026-09-01", "2026-09-30", "100000");

        // without it there is no baseline at all
        assertThat(ConsumptionCalculator.isUnusual(List.of(later), candidate)).isFalse();
    }

    @Test
    void theBaselineIsTheAverageOverAllDaysNotTheAverageOfAverages() {
        // 10 days with 100 and 91 days with 0: 100 kWh over 101 days (just under 1 a day), not the 5 of an average of
        // averages; the new interval is unusual above 3 x 100 x 10 / 101 = 29.70297... kWh
        List<Interval> earlier = List.of(interval("2026-06-01", "2026-06-11", "100"),
                interval("2026-06-11", "2026-09-10", "0"));

        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "29.702"))).isFalse();
        assertThat(ConsumptionCalculator.isUnusual(earlier, interval("2026-09-10", "2026-09-20", "29.703"))).isTrue();
    }

    // ---- the history validates what it is given

    @Test
    void aHistoryRefusesWhatCannotBeAMeter() {
        assertThatThrownBy(() -> new MeterHistory(d("2026-10-01"), v("0"), d("2026-11-01"), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeterHistory(d("2026-10-01"), v("0"), null, v("5"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> removed("2026-10-01", "0", "2026-09-30", "5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meter("2026-10-01", "0", reading("2026-09-30", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> removed("2026-10-01", "0", "2026-11-01", "5", reading("2026-11-02", "6")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meter("2026-10-01", "0", reading("2026-10-05", "2"), reading("2026-10-03", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsumptionCalculator.ParentPeriod(QUARTER_A, d("2026-10-05"), d("2026-10-04")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMeterRemovedOnTheDayItWasInstalledHasNoIntervalToItsFinalValue() {
        MeterHistory meter = removed("2026-10-01", "5", "2026-10-01", "5");

        assertThat(ConsumptionCalculator.intervals(meter)).isEmpty();
    }

    private static Quantity total(List<Slice> slices) {
        Quantity total = Quantity.ZERO;
        for (Slice slice : slices) {
            total = total.plus(known(slice.consumption()).consumption());
        }
        return total;
    }
}
