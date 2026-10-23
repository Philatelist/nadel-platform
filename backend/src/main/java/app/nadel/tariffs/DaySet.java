package app.nadel.tariffs;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A set of days: sorted, disjoint closed date ranges (feature 011). Pure and immutable. It says on which days a tariff
 * may charge a plot, for example the days a plot is connected to water ({@link ChargeCalculator#breakdown}); every
 * other charge uses {@link #ALL}.
 */
public final class DaySet {

    /** A closed range of days, {@code from} and {@code to} both included. */
    public record Range(LocalDate from, LocalDate to) {

        public Range {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("A range ends on or after its first day");
            }
        }
    }

    /** Every day. */
    public static final DaySet ALL = new DaySet(List.of(new Range(LocalDate.MIN, LocalDate.MAX)));

    /** No day. */
    public static final DaySet NONE = new DaySet(List.of());

    private final List<Range> ranges;

    private DaySet(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /** The days {@code from} to {@code to}, both included. */
    public static DaySet of(LocalDate from, LocalDate to) {
        return new DaySet(List.of(new Range(from, to)));
    }

    /** The union of the ranges, in any order; overlapping and adjacent ranges are merged. */
    public static DaySet of(Collection<Range> ranges) {
        List<Range> sorted = ranges.stream().sorted(Comparator.comparing(Range::from)).toList();
        List<Range> merged = new ArrayList<>();
        for (Range range : sorted) {
            if (!merged.isEmpty()) {
                Range last = merged.getLast();
                if (last.to().equals(LocalDate.MAX) || !range.from().isAfter(last.to().plusDays(1))) {
                    if (range.to().isAfter(last.to())) {
                        merged.set(merged.size() - 1, new Range(last.from(), range.to()));
                    }
                    continue;
                }
            }
            merged.add(range);
        }
        return new DaySet(merged);
    }

    /** The ranges, sorted and disjoint. */
    public List<Range> ranges() {
        return ranges;
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean contains(LocalDate day) {
        return ranges.stream().anyMatch(r -> !day.isBefore(r.from()) && !day.isAfter(r.to()));
    }

    /** The days in both sets. */
    public DaySet intersect(DaySet other) {
        List<Range> result = new ArrayList<>();
        for (Range a : ranges) {
            for (Range b : other.ranges) {
                LocalDate from = a.from().isAfter(b.from()) ? a.from() : b.from();
                LocalDate to = a.to().isBefore(b.to()) ? a.to() : b.to();
                if (!to.isBefore(from)) {
                    result.add(new Range(from, to));
                }
            }
        }
        return of(result);
    }

    /** The number of days of the set between {@code from} and {@code to}, both included; 0 when {@code to < from}. */
    public long count(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        long days = 0;
        for (Range range : intersect(of(from, to)).ranges) {
            days += ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        }
        return days;
    }

    /** The first day of the set between {@code from} and {@code to}, both included. */
    public Optional<LocalDate> first(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return Optional.empty();
        }
        return intersect(of(from, to)).ranges.stream().findFirst().map(Range::from);
    }

    /** The last day of the set between {@code from} and {@code to}, both included. */
    public Optional<LocalDate> last(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return Optional.empty();
        }
        List<Range> within = intersect(of(from, to)).ranges;
        return within.isEmpty() ? Optional.empty() : Optional.of(within.getLast().to());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DaySet set && ranges.equals(set.ranges);
    }

    @Override
    public int hashCode() {
        return ranges.hashCode();
    }

    @Override
    public String toString() {
        return "DaySet" + ranges;
    }
}
