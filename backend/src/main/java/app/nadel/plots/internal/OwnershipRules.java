package app.nadel.plots.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The rules of a plot's ownership timeline, free of persistence so they can be tested and reused: a change of owner
 * checks the timeline it would produce, and a correction (a later slice) checks the timeline after the correction in
 * the same way.
 * <p>
 * A {@link Period} is one owner of the plot with a closed range of days ({@code validTo} empty means "still
 * current"). The timeline of a plot is valid when
 * <ul>
 *   <li>on every day that has at least one owner, the shares of the owners add up to exactly one whole (exact
 *       fractions, never decimals; a sum too large to represent is not one whole);</li>
 *   <li>the same member never owns the plot in two overlapping periods;</li>
 *   <li>no period starts or ends after today (the Europe/Kyiv date, passed in).</li>
 * </ul>
 * Days without any owner are allowed (a plot may have no owner for a while).
 */
final class OwnershipRules {

    private OwnershipRules() {
    }

    /**
     * One owner of a plot for a range of days. {@code key} identifies the period for the caller (an index of the
     * request row, or an ownership id) so a {@link Violation} can be mapped back to a field; the rules never look at
     * it.
     */
    record Period(Object key, UUID memberId, Share share, LocalDate validFrom, @Nullable LocalDate validTo) {

        boolean overlaps(Period other) {
            return !validFrom.isAfter(endOrMax(other)) && !other.validFrom.isAfter(endOrMax(this));
        }

        boolean coversDay(LocalDate day) {
            return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
        }

        private static LocalDate endOrMax(Period period) {
            return period.validTo == null ? LocalDate.MAX : period.validTo;
        }
    }

    /** What is wrong with a timeline. */
    enum Kind {
        /** On some day with owners the shares do not add up to one whole. No {@code key}. */
        SHARES_NOT_ONE,
        /** The same member owns the plot in two overlapping periods; the key is that of the later-starting one. */
        MEMBER_OVERLAP,
        /** A period starts after today; the key is that of the period. */
        STARTS_IN_FUTURE,
        /** A period ends after today; the key is that of the period. */
        ENDS_IN_FUTURE
    }

    record Violation(Kind kind, @Nullable Object key) {
    }

    /** Why the date of a change of owner is refused. */
    enum DateProblem {
        /** The date is after today. */
        IN_FUTURE,
        /** The date is not later than the start date of a current owner. */
        NOT_AFTER_CURRENT_START,
        /** The date is not later than the end date of a past owner. */
        NOT_AFTER_PAST_END
    }

    /**
     * The last day of the previous owners when the new ones start on {@code effectiveFrom}: the day before. Calendar
     * arithmetic, so 1 March 2024 gives 29 February 2024 and 1 January 2026 gives 31 December 2025.
     */
    static LocalDate lastDayOfPreviousOwners(LocalDate effectiveFrom) {
        return effectiveFrom.minusDays(1);
    }

    /**
     * Checks the date of a change of owner against the plot's timeline before the change: it cannot be in the future
     * and must be later than the start date of every current owner and the end date of every past owner, so the
     * periods never overlap. The first problem found is returned.
     *
     * @param existing every period of the plot as it is now (current ones have no {@code validTo})
     */
    static Optional<DateProblem> checkChangeDate(LocalDate effectiveFrom, Collection<Period> existing,
            LocalDate today) {
        if (effectiveFrom.isAfter(today)) {
            return Optional.of(DateProblem.IN_FUTURE);
        }
        for (Period period : existing) {
            if (period.validTo() == null) {
                if (!effectiveFrom.isAfter(period.validFrom())) {
                    return Optional.of(DateProblem.NOT_AFTER_CURRENT_START);
                }
            } else if (!effectiveFrom.isAfter(period.validTo())) {
                return Optional.of(DateProblem.NOT_AFTER_PAST_END);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks a whole timeline (every period of one plot, as it would be after the change). Returns every problem
     * found, future dates first, then overlaps, then the sum of the shares (at most once).
     */
    static List<Violation> checkTimeline(Collection<Period> periods, LocalDate today) {
        List<Violation> violations = new ArrayList<>();
        for (Period period : periods) {
            if (period.validFrom().isAfter(today)) {
                violations.add(new Violation(Kind.STARTS_IN_FUTURE, period.key()));
            }
            if (period.validTo() != null && period.validTo().isAfter(today)) {
                violations.add(new Violation(Kind.ENDS_IN_FUTURE, period.key()));
            }
        }
        List<Period> ordered = periods.stream()
                .sorted(Comparator.comparing(Period::validFrom))
                .toList();
        for (int later = 0; later < ordered.size(); later++) {
            for (int earlier = 0; earlier < later; earlier++) {
                Period a = ordered.get(earlier);
                Period b = ordered.get(later);
                if (a.memberId().equals(b.memberId()) && a.overlaps(b)) {
                    violations.add(new Violation(Kind.MEMBER_OVERLAP, b.key()));
                }
            }
        }
        if (!sharesAddUpEveryDay(periods)) {
            violations.add(new Violation(Kind.SHARES_NOT_ONE, null));
        }
        return violations;
    }

    /**
     * The shares only change on a day an owner starts or the day after an owner ends, so it is enough to add up the
     * shares at those boundaries: every segment between two boundaries has the same owners as its first day.
     */
    private static boolean sharesAddUpEveryDay(Collection<Period> periods) {
        TreeSet<LocalDate> boundaries = new TreeSet<>();
        for (Period period : periods) {
            boundaries.add(period.validFrom());
            if (period.validTo() != null) {
                boundaries.add(period.validTo().plusDays(1));
            }
        }
        for (LocalDate day : boundaries) {
            Share sum = null;
            for (Period period : periods) {
                if (!period.coversDay(day)) {
                    continue;
                }
                try {
                    sum = sum == null ? period.share() : sum.plus(period.share());
                } catch (ArithmeticException tooLarge) {
                    return false; // a sum that does not fit a fraction is never one whole
                }
            }
            if (sum != null && !sum.isWhole()) {
                return false;
            }
        }
        return true;
    }
}
