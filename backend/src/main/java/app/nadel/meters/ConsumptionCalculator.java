package app.nadel.meters;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one place that works out how much a meter consumed (money-rules section 6). Pure: no Spring, no database, no
 * clock. The meter screens use it now and the electricity charges of feature 011 will use the same code, so the two can
 * never disagree.
 * <p>
 * The points of a meter. A meter's history is a row of points on dates: its initial reading on the installation date,
 * every approved reading, and its final reading on the removal date. A reading on the installation or removal date takes
 * the place of that boundary point. An interval is the consumption between two neighbouring points.
 * <p>
 * Value at a date. The point on that date, else the value interpolated linearly by days between the nearest points
 * before and after it, {@code (v0 * N + (v1 - v0) * k) / N} with {@code N} the days between the points and {@code k} the
 * days from the earlier one (marked {@link ValueAt.Calculated}), else unknown ({@link ValueAt.Unknown}). Every value is an
 * exact {@link Quantity}: nothing is rounded here, so the parts of a range add up to the whole.
 * <p>
 * Range consumption. {@code value at "to" - value at "from"}, clipped to the days the meter worked, and summed over the
 * meters of a plot (a chain: a replacement, a counter that wrapped around). A range that is not covered by readings on
 * its far end is {@link RangeConsumption.Unknown}, never a guess.
 * <p>
 * The days are whole days between dates; a consumption "from 01.10 to 15.10" is the difference of the values on those
 * two dates.
 */
public final class ConsumptionCalculator {

    /** The usual consumption is measured over this many days before the new reading. */
    static final int UNUSUAL_WINDOW_DAYS = 180;

    /** A daily consumption above this multiple of the usual one is unusual. */
    static final long UNUSUAL_FACTOR = 3;

    private ConsumptionCalculator() {
    }

    /** An approved reading: a value on the date it was taken. */
    public record Reading(LocalDate takenOn, MeterValue value) {

        public Reading {
            Objects.requireNonNull(takenOn, "takenOn");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Everything the calculation needs to know about one meter: when it was installed and with what, when it was removed
     * and with what, and its approved readings in ascending date order, one per date, within the days it worked.
     * Pending, rejected and superseded readings are not part of it.
     */
    public record MeterHistory(LocalDate installedOn, MeterValue initial, @Nullable LocalDate removedOn,
            @Nullable MeterValue finalValue, List<Reading> readings) {

        /**
         * @throws IllegalArgumentException when the removal date and the final value are not given together, the removal
         *                                  is before the installation, or the readings are not in strictly ascending
         *                                  date order within the working period
         */
        public MeterHistory {
            Objects.requireNonNull(installedOn, "installedOn");
            Objects.requireNonNull(initial, "initial");
            if ((removedOn == null) != (finalValue == null)) {
                throw new IllegalArgumentException("The removal date and the final value come together");
            }
            if (removedOn != null && removedOn.isBefore(installedOn)) {
                throw new IllegalArgumentException("A meter is not removed before it is installed");
            }
            readings = List.copyOf(readings);
            LocalDate previous = null;
            for (Reading reading : readings) {
                LocalDate date = reading.takenOn();
                if (previous != null && !date.isAfter(previous)) {
                    throw new IllegalArgumentException("The readings of a meter are in ascending date order, one per date");
                }
                if (date.isBefore(installedOn) || (removedOn != null && date.isAfter(removedOn))) {
                    throw new IllegalArgumentException("A reading is dated within the working period of its meter");
                }
                previous = date;
            }
        }
    }

    /** The consumption between two neighbouring points of a meter, over the days from one date to the other. */
    public record Interval(LocalDate from, LocalDate to, Quantity consumption) {

        public Interval {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(consumption, "consumption");
        }

        /** The interval between the value {@code fromValue} on {@code from} and {@code toValue} on {@code to}. */
        public static Interval of(LocalDate from, MeterValue fromValue, LocalDate to, MeterValue toValue) {
            return new Interval(from, to, Quantity.of(toValue).minus(Quantity.of(fromValue)));
        }

        /** The number of days from {@code from} to {@code to}. */
        public long days() {
            return ChronoUnit.DAYS.between(from, to);
        }
    }

    /** The value of a meter on a date: a reading, a value calculated from the neighbouring ones, or not known. */
    public sealed interface ValueAt {

        /** The reading (or the initial or final value) on that very date. */
        record Real(MeterValue value) implements ValueAt {
        }

        /** Interpolated by days between the nearest points on both sides; shown as "calculated". */
        record Calculated(Quantity value) implements ValueAt {
        }

        /**
         * There is no point after the date (or none before it). {@code lastReadingOn} is the date of the last point
         * before it, the date after which there is nothing, or null when there is no point before the date either.
         */
        record Unknown(@Nullable LocalDate lastReadingOn) implements ValueAt {
        }
    }

    /** The consumption over a date range: a quantity, or not known. */
    public sealed interface RangeConsumption {

        /**
         * @param consumption the exact consumption
         * @param calculated  true when the value at an end of the range was interpolated, so the number is calculated
         *                    and not read off two readings
         */
        record Known(Quantity consumption, boolean calculated) implements RangeConsumption {

            public Known {
                Objects.requireNonNull(consumption, "consumption");
            }
        }

        /** A value at the end of the range is not known; nothing is guessed. */
        record Unknown(@Nullable LocalDate lastReadingOn) implements RangeConsumption {
        }
    }

    /**
     * A period in which a meter had one parent in the tree, as stored in {@code meter_parent}: both dates are days of
     * the period, {@code validTo} is null while it lasts. A meter moved to another parent on day D has one period ending
     * on D - 1 and the next one starting on D.
     */
    public record ParentPeriod(UUID parentMeterId, LocalDate validFrom, @Nullable LocalDate validTo) {

        public ParentPeriod {
            Objects.requireNonNull(parentMeterId, "parentMeterId");
            Objects.requireNonNull(validFrom, "validFrom");
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("A parent period does not end before it starts");
            }
        }
    }

    /** The part of a range counted under one parent, from {@code from} to {@code to}. */
    public record Slice(UUID parentMeterId, LocalDate from, LocalDate to, RangeConsumption consumption) {
    }

    // ---- intervals

    /**
     * The intervals of a meter between its neighbouring points, oldest first: from the installation to the first
     * reading, between readings, and from the last reading to the removal.
     */
    public static List<Interval> intervals(MeterHistory history) {
        List<Interval> result = new ArrayList<>();
        Map.Entry<LocalDate, MeterValue> previous = null;
        for (Map.Entry<LocalDate, MeterValue> point : points(history).entrySet()) {
            if (previous != null) {
                result.add(Interval.of(previous.getKey(), previous.getValue(), point.getKey(), point.getValue()));
            }
            previous = point;
        }
        return result;
    }

    // ---- value at a date

    /** The value of the meter on {@code date}; see the class description. */
    public static ValueAt valueAt(MeterHistory history, LocalDate date) {
        Objects.requireNonNull(date, "date");
        NavigableMap<LocalDate, MeterValue> points = points(history);
        MeterValue exact = points.get(date);
        if (exact != null) {
            return new ValueAt.Real(exact);
        }
        Map.Entry<LocalDate, MeterValue> before = points.lowerEntry(date);
        Map.Entry<LocalDate, MeterValue> after = points.higherEntry(date);
        if (before == null || after == null) {
            return new ValueAt.Unknown(before == null ? null : before.getKey());
        }
        long daysBetween = ChronoUnit.DAYS.between(before.getKey(), after.getKey());
        long daysSince = ChronoUnit.DAYS.between(before.getKey(), date);
        Quantity v0 = Quantity.of(before.getValue());
        Quantity v1 = Quantity.of(after.getValue());
        return new ValueAt.Calculated(v0.plus(v1.minus(v0).times(daysSince).dividedBy(daysBetween)));
    }

    // ---- range consumption

    /**
     * The consumption of one meter from {@code from} to {@code to}, clipped to the days the meter worked: a range that
     * does not overlap them, or overlaps them in a single day, consumed nothing.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    public static RangeConsumption rangeConsumption(MeterHistory history, LocalDate from, LocalDate to) {
        requireRange(from, to);
        LocalDate start = from.isBefore(history.installedOn()) ? history.installedOn() : from;
        LocalDate end = history.removedOn() != null && to.isAfter(history.removedOn()) ? history.removedOn() : to;
        if (!start.isBefore(end)) {
            return new RangeConsumption.Known(Quantity.ZERO, false);
        }
        ValueAt valueAtStart = valueAt(history, start);
        ValueAt valueAtEnd = valueAt(history, end);
        if (valueAtEnd instanceof ValueAt.Unknown unknown) {
            return new RangeConsumption.Unknown(unknown.lastReadingOn());
        }
        if (valueAtStart instanceof ValueAt.Unknown unknown) {
            return new RangeConsumption.Unknown(unknown.lastReadingOn());
        }
        boolean calculated = valueAtStart instanceof ValueAt.Calculated || valueAtEnd instanceof ValueAt.Calculated;
        return new RangeConsumption.Known(quantity(valueAtEnd).minus(quantity(valueAtStart)), calculated);
    }

    /**
     * The consumption of a plot (or a node) whose meters follow one another: the sum of the consumption of each meter in
     * its own working days, so a replacement or a counter that wrapped around adds up (money-rules 6.2). It is unknown
     * when any meter's part is unknown; the date it reports is the latest "no reading after" of the unknown parts.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    public static RangeConsumption rangeConsumption(List<MeterHistory> chain, LocalDate from, LocalDate to) {
        requireRange(from, to);
        Quantity total = Quantity.ZERO;
        boolean calculated = false;
        boolean unknown = false;
        LocalDate lastReadingOn = null;
        for (MeterHistory history : chain) {
            switch (rangeConsumption(history, from, to)) {
                case RangeConsumption.Known known -> {
                    total = total.plus(known.consumption());
                    calculated |= known.calculated();
                }
                case RangeConsumption.Unknown part -> {
                    unknown = true;
                    if (part.lastReadingOn() != null
                            && (lastReadingOn == null || part.lastReadingOn().isAfter(lastReadingOn))) {
                        lastReadingOn = part.lastReadingOn();
                    }
                }
            }
        }
        return unknown ? new RangeConsumption.Unknown(lastReadingOn) : new RangeConsumption.Known(total, calculated);
    }

    /**
     * The consumption of a range split by the parent the node had in it: for every parent period that overlaps the range,
     * the slice from the later of the range start and the period start to the earlier of the range end and the day after
     * the period end (the day the next period starts). Neighbouring slices share their boundary day, so they add up to
     * the consumption of the whole range. A plot moved from quarter A to quarter B on 15.10 and shown for 01.10 to 31.10
     * has the slices 01.10-15.10 under A and 15.10-31.10 under B. Days with no parent are in no slice. Slices are
     * ordered by their start.
     *
     * @param chain   the meters of the node, in the sense of {@link #rangeConsumption(List, LocalDate, LocalDate)}
     * @param periods the parent periods of the node, in any order
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    public static List<Slice> splitByParent(List<MeterHistory> chain, List<ParentPeriod> periods, LocalDate from,
            LocalDate to) {
        requireRange(from, to);
        List<Slice> slices = new ArrayList<>();
        for (ParentPeriod period : periods.stream().sorted(Comparator.comparing(ParentPeriod::validFrom)).toList()) {
            LocalDate start = period.validFrom().isAfter(from) ? period.validFrom() : from;
            LocalDate periodEnd = period.validTo() == null ? to : period.validTo().plusDays(1);
            LocalDate end = periodEnd.isBefore(to) ? periodEnd : to;
            boolean overlaps = start.isBefore(end);
            boolean pointInside = from.equals(to) && !from.isBefore(period.validFrom())
                    && (period.validTo() == null || !from.isAfter(period.validTo()));
            if (overlaps || pointInside) {
                slices.add(new Slice(period.parentMeterId(), start, end, rangeConsumption(chain, start, end)));
            }
        }
        return slices;
    }

    // ---- unusual consumption

    /**
     * Whether a new interval consumed much more per day than the meter usually does. The usual consumption is the average
     * per day over the earlier intervals that ended in the 180 days before the new reading, no later than the day the
     * new interval starts. The new interval is unusual when its daily consumption is more than three times that, that is
     * {@code c * sum(days) > 3 * sum(c) * n} for a consumption {@code c} over {@code n} days. There is no warning without
     * such an earlier interval, when the usual consumption is zero, or for an interval of no days.
     *
     * @param earlier   the earlier intervals of the same meter (counted readings only), in any order
     * @param candidate the interval that the new reading closes
     */
    public static boolean isUnusual(List<Interval> earlier, Interval candidate) {
        long candidateDays = candidate.days();
        if (candidateDays <= 0) {
            return false;
        }
        LocalDate windowStart = candidate.to().minusDays(UNUSUAL_WINDOW_DAYS);
        Quantity baselineConsumption = Quantity.ZERO;
        long baselineDays = 0;
        for (Interval interval : earlier) {
            if (interval.days() > 0 && interval.to().isAfter(windowStart) && !interval.to().isAfter(candidate.from())) {
                baselineConsumption = baselineConsumption.plus(interval.consumption());
                baselineDays += interval.days();
            }
        }
        if (baselineDays == 0 || baselineConsumption.signum() <= 0) {
            return false;
        }
        Quantity candidateSide = candidate.consumption().times(baselineDays);
        Quantity baselineSide = baselineConsumption.times(UNUSUAL_FACTOR).times(candidateDays);
        return candidateSide.compareTo(baselineSide) > 0;
    }

    // ---- helpers

    /** The initial point, the final point and the readings by date; a reading wins over a boundary point on its date. */
    private static NavigableMap<LocalDate, MeterValue> points(MeterHistory history) {
        NavigableMap<LocalDate, MeterValue> points = new TreeMap<>();
        points.put(history.installedOn(), history.initial());
        if (history.removedOn() != null) {
            points.put(history.removedOn(), history.finalValue());
        }
        for (Reading reading : history.readings()) {
            points.put(reading.takenOn(), reading.value());
        }
        return points;
    }

    private static Quantity quantity(ValueAt value) {
        return switch (value) {
            case ValueAt.Real real -> Quantity.of(real.value());
            case ValueAt.Calculated calculated -> calculated.value();
            case ValueAt.Unknown unknown -> throw new IllegalStateException("An unknown value has no quantity");
        };
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A range does not end before it starts");
        }
    }
}
