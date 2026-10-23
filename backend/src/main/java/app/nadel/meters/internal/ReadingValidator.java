package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterValue;
import app.nadel.problem.ProblemCodes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The rules a reading must follow before it counts (spec 010, section 2.4), and the warning it may carry. Pure: no
 * Spring, no database, no clock; the reading commands hand over the meter, its counted readings and today.
 * <p>
 * "Counted" means the approved readings of the meter other than those the new reading takes the place of: a reading of
 * the same date (it is superseded) and, for a correction, the corrected reading itself. The points of the meter are
 * its initial reading on the installation date, the counted readings and its final reading on the removal date; a
 * point on the date of the new reading is not a neighbour (the new reading takes its place, as in
 * {@link ConsumptionCalculator}).
 * <p>
 * The checks, in this order: the date is not after today ({@code READING_DATE_IN_FUTURE} on {@code takenOn}); the date
 * is within the working period of the meter ({@code READING_DATE_OUTSIDE_METER} on {@code takenOn}); the value is not
 * lower than the point before it and not higher than the point after it ({@code READING_OUT_OF_ORDER} on
 * {@code value}). Each violation carries its parameters: ISO dates and values with three decimals.
 */
final class ReadingValidator {

    private ReadingValidator() {
    }

    /** The working period of a meter and its boundary values. */
    record MeterSpan(LocalDate installedOn, MeterValue initial, @Nullable LocalDate removedOn,
            @Nullable MeterValue finalValue) {

        MeterSpan {
            Objects.requireNonNull(installedOn, "installedOn");
            Objects.requireNonNull(initial, "initial");
            if ((removedOn == null) != (finalValue == null)) {
                throw new IllegalArgumentException("The removal date and the final value come together");
            }
        }

        static MeterSpan of(MeterRecord meter) {
            return new MeterSpan(meter.getInstalledOn(), meter.getInitialValue(), meter.getRemovedOn(),
                    meter.getFinalValue());
        }
    }

    /** The nearest points of the meter before and after a date; null where there is none. */
    record Neighbours(@Nullable Reading previous, @Nullable Reading next) {

        /**
         * The days a change on {@code date} reaches: from the day after the previous point (or the date itself) to the
         * day before the next point (or the date itself). A charge read the meter on a closed range of days from one
         * point to another ({@code ReadingUsage}); a change on {@code date} alters it exactly when that range meets
         * this span. The neighbouring points themselves stay out: a new reading after a charged interval (which ends
         * on the previous point) does not touch it (feature 011, the span fix).
         */
        LocalDate spanFrom(LocalDate date) {
            return previous == null ? date : previous.takenOn().plusDays(1);
        }

        LocalDate spanTo(LocalDate date) {
            return next == null ? date : next.takenOn().minusDays(1);
        }
    }

    /** A broken rule: the problem code, the field of the request it is about and the parameters of the message. */
    record Violation(String code, String field, Map<String, String> params) {

        Violation {
            params = Map.copyOf(params);
        }
    }

    /** The warnings of a saved reading (the {@code ReadingWarning} codes of the API contract). */
    enum Warning {
        UNUSUAL_CONSUMPTION
    }

    /**
     * The nearest points of the meter around {@code date}, not counting a point on {@code date} itself.
     *
     * @param counted the counted readings of the meter, in any order
     */
    static Neighbours neighbours(MeterSpan meter, List<Reading> counted, LocalDate date) {
        NavigableMap<LocalDate, MeterValue> points = points(meter, counted);
        points.remove(date);
        Map.Entry<LocalDate, MeterValue> before = points.lowerEntry(date);
        Map.Entry<LocalDate, MeterValue> after = points.higherEntry(date);
        return new Neighbours(before == null ? null : new Reading(before.getKey(), before.getValue()),
                after == null ? null : new Reading(after.getKey(), after.getValue()));
    }

    /**
     * The first rule a reading of {@code value} on {@code takenOn} breaks, or empty when it may count.
     *
     * @param counted the counted readings of the meter (see the class description), in any order
     * @param today   today in the cooperative's time zone (Europe/Kyiv)
     */
    static Optional<Violation> check(MeterSpan meter, List<Reading> counted, LocalDate today, LocalDate takenOn,
            MeterValue value) {
        if (takenOn.isAfter(today)) {
            return Optional.of(new Violation(ProblemCodes.READING_DATE_IN_FUTURE, "takenOn",
                    Map.of("today", today.toString())));
        }
        if (takenOn.isBefore(meter.installedOn())
                || (meter.removedOn() != null && takenOn.isAfter(meter.removedOn()))) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("installedOn", meter.installedOn().toString());
            if (meter.removedOn() != null) {
                params.put("removedOn", meter.removedOn().toString());
            }
            return Optional.of(new Violation(ProblemCodes.READING_DATE_OUTSIDE_METER, "takenOn", params));
        }
        Neighbours neighbours = neighbours(meter, counted, takenOn);
        Reading previous = neighbours.previous();
        if (previous != null && value.compareTo(previous.value()) < 0) {
            return Optional.of(new Violation(ProblemCodes.READING_OUT_OF_ORDER, "value", Map.of(
                    "previousTakenOn", previous.takenOn().toString(), "previousValue", previous.value().toString())));
        }
        Reading next = neighbours.next();
        if (next != null && value.compareTo(next.value()) > 0) {
            return Optional.of(new Violation(ProblemCodes.READING_OUT_OF_ORDER, "value", Map.of(
                    "nextTakenOn", next.takenOn().toString(), "nextValue", next.value().toString())));
        }
        return Optional.empty();
    }

    /**
     * The warnings of a reading that passed {@link #check}: {@link Warning#UNUSUAL_CONSUMPTION} when the interval it
     * closes is unusual by {@link ConsumptionCalculator#isUnusual} (more than three times the usual daily consumption
     * over the counted intervals that ended in the 180 days before it; nothing without such an interval).
     *
     * @param counted the counted readings of the meter without the new one, in any order
     */
    static List<Warning> warnings(MeterSpan meter, List<Reading> counted, LocalDate takenOn, MeterValue value) {
        List<Interval> intervals = ConsumptionCalculator.intervals(history(meter, counted, takenOn, value));
        Interval candidate = intervals.stream().filter(interval -> interval.to().equals(takenOn)).findFirst()
                .orElse(null);
        if (candidate == null) {
            return List.of(); // a reading on the installation date closes no interval
        }
        List<Interval> earlier = intervals.stream().filter(interval -> interval != candidate).toList();
        return ConsumptionCalculator.isUnusual(earlier, candidate) ? List.of(Warning.UNUSUAL_CONSUMPTION) : List.of();
    }

    /**
     * The interval that a counted reading on {@code takenOn} closes, from the point before it; empty for a reading on
     * the installation date, which has no point before it.
     *
     * @param counted the counted readings of the meter, the reading on {@code takenOn} included or not
     */
    static Optional<Interval> intervalEndingAt(MeterSpan meter, List<Reading> counted, LocalDate takenOn,
            MeterValue value) {
        Reading previous = neighbours(meter, counted, takenOn).previous();
        return previous == null ? Optional.empty()
                : Optional.of(Interval.of(previous.takenOn(), previous.value(), takenOn, value));
    }

    /** The history of the meter with the readings and one more on {@code takenOn}, which wins over a reading there. */
    private static MeterHistory history(MeterSpan meter, List<Reading> counted, LocalDate takenOn, MeterValue value) {
        List<Reading> readings = new ArrayList<>(counted.stream()
                .filter(reading -> !reading.takenOn().equals(takenOn)).toList());
        readings.add(new Reading(takenOn, value));
        readings.sort(Comparator.comparing(Reading::takenOn));
        return new MeterHistory(meter.installedOn(), meter.initial(), meter.removedOn(), meter.finalValue(),
                readings);
    }

    private static NavigableMap<LocalDate, MeterValue> points(MeterSpan meter, List<Reading> counted) {
        NavigableMap<LocalDate, MeterValue> points = new TreeMap<>();
        points.put(meter.installedOn(), meter.initial());
        if (meter.removedOn() != null) {
            points.put(meter.removedOn(), meter.finalValue());
        }
        for (Reading reading : counted) {
            points.put(reading.takenOn(), reading.value());
        }
        return points;
    }
}
