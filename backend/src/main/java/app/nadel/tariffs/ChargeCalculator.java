package app.nadel.tariffs;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one place that works out what a tariff charges a plot for a billing month (money-rules section 4). Pure: no
 * Spring, no database, no clock. The expected-amount preview ({@link #calculate}) and the ledger's month run (feature
 * 011, {@link #breakdown} with the days the tariff may charge) use the same code, so the two can never disagree.
 * <p>
 * The rule, in short. The versions of a tariff are ordered by {@code validFrom}; a version applies until the day before
 * the next one starts. Each version counts for the days it overlaps the month, divided by the days in the month
 * {@code D}. A recurring version contributes {@code rate x units x share x days / D} where {@code units} is 1 per plot
 * or the area in sotkas (m2 / 100, exact), and {@code share} is 1 for monthly, 1/3 quarterly and 1/12 yearly. A
 * fixed one-time version contributes its whole rate in the month of its due date (when that version is valid on the due
 * date) and nothing in any other month; it is never split by days. Every term is added up as one exact fraction with the
 * common denominator {@code D x 12}, so nothing is rounded on the way, and {@link Money#ofQuotient} rounds the sum once,
 * HALF_UP, at the end.
 */
public final class ChargeCalculator {

    private static final BigDecimal MONTHS_IN_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal SOTKA_M2 = BigDecimal.valueOf(100);

    private ChargeCalculator() {
    }

    /**
     * A version together with the days it overlaps the month ({@code from..to}) and the last day it is valid.
     */
    private record Overlap(VersionTerms terms, @Nullable LocalDate validTo, LocalDate from, LocalDate to) {
    }

    /**
     * The expected charge of one tariff for a plot of {@code areaM2} square metres in {@code month}.
     *
     * @param versions every version of the tariff, in any order
     * @param areaM2   the area of the plot in square metres
     * @param month    the billing month
     * @return empty when no version applies on any day of the month (the tariff is not listed for that month);
     *         {@link ExpectedCharge.ByReadings} (with the basis of the latest such version) when a per-kWh or
     *         per-m3 version overlaps the month; otherwise the
     *         {@link ExpectedCharge.Amount}, rounded once (the amount of {@link #breakdown} on every day)
     */
    public static Optional<ExpectedCharge> calculate(List<VersionTerms> versions, BigDecimal areaM2, YearMonth month) {
        Objects.requireNonNull(areaM2, "areaM2");
        Objects.requireNonNull(month, "month");
        List<Overlap> overlaps = overlaps(versions, month);
        if (overlaps.isEmpty()) {
            return Optional.empty();
        }
        Optional<Overlap> meterBased = overlaps.reversed().stream().filter(o -> isMeterBased(o.terms())).findFirst();
        if (meterBased.isPresent()) {
            return Optional.of(new ExpectedCharge.ByReadings(meterBased.get().terms().basis()));
        }
        return Optional.of(new ExpectedCharge.Amount(
                breakdown(versions, areaM2, month, DaySet.ALL).orElseThrow().amount()));
    }

    /**
     * One part of a {@link Breakdown}: what one version contributes.
     *
     * @param terms the version
     * @param days  the days it counts for: the applicable days of the month on which it is valid; 1 for a one-time
     *              fee (its due date)
     * @param units 1 per plot (and for a one-time fee), the area in sotkas (m2 / 100, exact) per sotka
     * @param from  the first day counted
     * @param to    the last day counted
     */
    public record Part(VersionTerms terms, long days, BigDecimal units, LocalDate from, LocalDate to) {
    }

    /**
     * What one tariff charges a plot for a month, with the versions behind it.
     *
     * @param amount the amount, rounded once
     * @param parts  the versions that contribute, oldest first; empty when the amount is 0.00 because a one-time fee
     *               is due in another month (or on a day that is not applicable)
     */
    public record Breakdown(Money amount, List<Part> parts) {

        public Breakdown {
            Objects.requireNonNull(amount, "amount");
            parts = List.copyOf(parts);
        }
    }

    /**
     * The amount of one tariff for a plot and a month, counting only the {@code applicable} days (the ledger's month
     * run; {@link DaySet#ALL} gives the amount of {@link #calculate}). Each recurring version counts for the days of
     * the month on which it is valid and that are applicable, over the days in the month; a one-time fee counts in its
     * due month only when that version is valid on the due date and the due date is applicable. One exact sum, rounded
     * once.
     *
     * @param versions   every version of the tariff, in any order
     * @param areaM2     the area of the plot in square metres
     * @param month      the billing month
     * @param applicable the days the tariff may charge the plot
     * @return empty when no version is valid on an applicable day of the month, or when a per-kWh or per-m3 version
     *         is (such a tariff is charged by meter readings, never by the month)
     */
    public static Optional<Breakdown> breakdown(List<VersionTerms> versions, BigDecimal areaM2, YearMonth month,
            DaySet applicable) {
        Objects.requireNonNull(areaM2, "areaM2");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(applicable, "applicable");
        List<Overlap> overlaps = overlaps(versions, month).stream()
                .filter(o -> applicable.count(o.from(), o.to()) > 0).toList();
        if (overlaps.isEmpty() || overlaps.stream().anyMatch(o -> isMeterBased(o.terms()))) {
            return Optional.empty();
        }
        BigDecimal daysInMonth = BigDecimal.valueOf(month.lengthOfMonth());
        BigDecimal sotkas = areaM2.movePointLeft(2); // exact: m2 / 100
        BigDecimal numerator = BigDecimal.ZERO;
        List<Part> parts = new ArrayList<>();
        for (Overlap overlap : overlaps) {
            VersionTerms terms = overlap.terms();
            BigDecimal rate = terms.rate().value();
            if (terms.basis() == Basis.FIXED_ONE_TIME) {
                LocalDate due = terms.dueDate();
                if (dueInMonthAndValid(overlap, month) && due != null && applicable.contains(due)) {
                    numerator = numerator.add(rate.multiply(daysInMonth).multiply(MONTHS_IN_YEAR));
                    parts.add(new Part(terms, 1, BigDecimal.ONE, due, due));
                }
            } else {
                BigDecimal units = terms.basis() == Basis.PER_AREA_SOTKA ? sotkas : BigDecimal.ONE;
                long days = applicable.count(overlap.from(), overlap.to());
                numerator = numerator.add(rate.multiply(units)
                        .multiply(BigDecimal.valueOf(days))
                        .multiply(BigDecimal.valueOf(periodsPerYearFactor(terms.frequency()))));
                parts.add(new Part(terms, days, units, applicable.first(overlap.from(), overlap.to()).orElseThrow(),
                        applicable.last(overlap.from(), overlap.to()).orElseThrow()));
            }
        }
        return Optional.of(new Breakdown(Money.ofQuotient(numerator, daysInMonth.multiply(MONTHS_IN_YEAR)), parts));
    }

    /**
     * The version a listing of the month shows: the version valid on its due date for a one-time fee due in the month,
     * otherwise the version valid on the last day of the month that the tariff covers.
     *
     * @return empty exactly when {@link #calculate} is empty
     */
    public static Optional<VersionTerms> shownVersion(List<VersionTerms> versions, YearMonth month) {
        List<Overlap> overlaps = overlaps(versions, month);
        if (overlaps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(overlaps.stream()
                .filter(o -> o.terms().basis() == Basis.FIXED_ONE_TIME && dueInMonthAndValid(o, month))
                .findFirst()
                .orElse(overlaps.getLast())
                .terms());
    }

    /** The versions that apply on at least one day of the month, oldest first. */
    private static List<Overlap> overlaps(List<VersionTerms> versions, YearMonth month) {
        List<VersionTerms> sorted = versions.stream().sorted(Comparator.comparing(VersionTerms::validFrom)).toList();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<Overlap> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            VersionTerms terms = sorted.get(i);
            LocalDate validTo = i + 1 < sorted.size() ? sorted.get(i + 1).validFrom().minusDays(1) : null;
            LocalDate from = terms.validFrom().isAfter(first) ? terms.validFrom() : first;
            LocalDate to = validTo == null || validTo.isAfter(last) ? last : validTo;
            if (!to.isBefore(from)) {
                result.add(new Overlap(terms, validTo, from, to));
            }
        }
        return result;
    }

    private static boolean isMeterBased(VersionTerms terms) {
        return terms.basis() == Basis.PER_KWH || terms.basis() == Basis.PER_M3;
    }

    private static boolean dueInMonthAndValid(Overlap overlap, YearMonth month) {
        LocalDate due = overlap.terms().dueDate();
        return due != null && YearMonth.from(due).equals(month)
                && !due.isBefore(overlap.terms().validFrom())
                && (overlap.validTo() == null || !due.isAfter(overlap.validTo()));
    }

    /** Twelve times the share of a year that one month of the frequency stands for: monthly 12, quarterly 4, yearly 1. */
    private static long periodsPerYearFactor(Frequency frequency) {
        return switch (frequency) {
            case MONTHLY -> 12;
            case QUARTERLY -> 4;
            case YEARLY -> 1;
            case ONE_OFF -> throw new IllegalStateException("A one-off frequency goes only with a fixed one-time amount");
        };
    }
}
