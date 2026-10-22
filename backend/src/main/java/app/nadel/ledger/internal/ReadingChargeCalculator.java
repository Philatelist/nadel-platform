package app.nadel.ledger.internal;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one tariff charges for the consumption of one meter interval (feature 011 §2.4, money-rules 6). Pure: no Spring,
 * no database, no clock; every row of the feature's worked examples is a unit test ({@code ReadingChargeCalculatorTests}).
 * <p>
 * The days of an interval run from the day after the previous point to the day of the reading, both included
 * ({@code N} days). The consumption is spread evenly over them: each version of the tariff that is charged by the meter
 * ({@code PER_KWH} for electricity, {@code PER_M3} for water) gets {@code consumption x d / N} for the {@code d}
 * applicable days it is valid on, priced at its rate. Every part stays exact and the sum
 * {@code (sum of rate x d x numerator) / (N x denominator)} is rounded once by {@link Money#ofQuotient}; rounding each
 * part would give a different amount (7 kWh at 4.3250 and 1 kWh at 4.3350 is 34.61, not 34.62). Days that are not
 * applicable (for water: the days the plot is not connected) are dropped with their share of the consumption.
 * <p>
 * Fixed-percentage losses are a second amount over the same parts: {@code percent / 100} of each part's quantity at the
 * same rate, added exactly and rounded once on their own.
 */
final class ReadingChargeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ReadingChargeCalculator() {
    }

    /** The outcome of {@link #price}. */
    sealed interface Result {
    }

    /**
     * One version's share of an interval.
     *
     * @param terms    the version
     * @param days     the applicable days of the interval it is valid on
     * @param quantity the exact consumption of those days
     * @param from     the first such day
     * @param to       the last such day
     */
    record PricedPart(VersionTerms terms, long days, Quantity quantity, LocalDate from, LocalDate to) {
    }

    /**
     * The fixed-percentage losses of an interval.
     *
     * @param percent  the percentage (two decimals)
     * @param quantity the exact losses ({@code consumption x percent / 100} of the applicable days)
     * @param amount   rounded once
     * @param parts    the parts with their losses quantity, in the order of the consumption parts
     */
    record Losses(BigDecimal percent, Quantity quantity, Money amount, List<PricedPart> parts) {
    }

    /**
     * The interval is priced.
     *
     * @param amount the consumption charge, rounded once (may be 0.00 for a zero consumption)
     * @param parts  the versions behind it, oldest first
     * @param losses the losses, null when no percentage is given (or it is zero)
     */
    record Priced(Money amount, List<PricedPart> parts, @Nullable Losses losses) implements Result {
    }

    /** Some applicable days of the interval have no version charged by the meter: nothing is charged. */
    record NotCovered(long uncoveredDays) implements Result {
    }

    /**
     * The tariff does not charge this interval by the meter at all: no applicable day, or no version of the meter's
     * basis on any applicable day (such a tariff is charged by the month run).
     */
    record NotApplicable() implements Result {
    }

    /**
     * @param previousPoint the date of the previous point of the meter (the interval starts the day after)
     * @param readingOn     the date of the point that closes the interval, after {@code previousPoint}
     * @param consumption   the exact consumption of the interval, not negative
     * @param versions      every version of the tariff, in any order
     * @param basis         the basis charged by this meter ({@code PER_KWH} or {@code PER_M3})
     * @param applicable    the days the tariff may charge the plot ({@link DaySet#ALL} for electricity)
     * @param lossesPercent the fixed losses percentage, or null for none
     */
    static Result price(LocalDate previousPoint, LocalDate readingOn, Quantity consumption, List<VersionTerms> versions,
            Basis basis, DaySet applicable, @Nullable BigDecimal lossesPercent) {
        Objects.requireNonNull(consumption, "consumption");
        if (!readingOn.isAfter(previousPoint)) {
            throw new IllegalArgumentException("An interval ends after its previous point");
        }
        if (consumption.signum() < 0) {
            throw new IllegalArgumentException("The consumption of an interval is not negative");
        }
        LocalDate first = previousPoint.plusDays(1);
        long n = ChronoUnit.DAYS.between(previousPoint, readingOn);
        long applicableDays = applicable.count(first, readingOn);
        if (applicableDays == 0) {
            return new NotApplicable();
        }

        List<VersionTerms> sorted = versions.stream().sorted(Comparator.comparing(VersionTerms::validFrom)).toList();
        List<PricedPart> parts = new ArrayList<>();
        long covered = 0;
        for (int i = 0; i < sorted.size(); i++) {
            VersionTerms terms = sorted.get(i);
            LocalDate validTo = i + 1 < sorted.size() ? sorted.get(i + 1).validFrom().minusDays(1) : null;
            LocalDate from = terms.validFrom().isAfter(first) ? terms.validFrom() : first;
            LocalDate to = validTo == null || validTo.isAfter(readingOn) ? readingOn : validTo;
            long days = applicable.count(from, to);
            if (days == 0 || terms.basis() != basis) {
                continue;
            }
            covered += days;
            parts.add(new PricedPart(terms, days, consumption.times(days).dividedBy(n),
                    applicable.first(from, to).orElseThrow(), applicable.last(from, to).orElseThrow()));
        }
        if (parts.isEmpty()) {
            return new NotApplicable();
        }
        if (covered < applicableDays) {
            return new NotCovered(applicableDays - covered);
        }

        // sum of rate x d x numerator over N x denominator: exact, rounded once
        BigDecimal numerator = BigDecimal.ZERO;
        for (PricedPart part : parts) {
            numerator = numerator.add(part.terms().rate().value().multiply(BigDecimal.valueOf(part.days())));
        }
        numerator = numerator.multiply(new BigDecimal(consumption.numerator()));
        BigDecimal denominator = BigDecimal.valueOf(n).multiply(new BigDecimal(consumption.denominator()));
        Money amount = Money.ofQuotient(numerator, denominator);

        Losses losses = null;
        if (lossesPercent != null && lossesPercent.signum() > 0) {
            losses = new Losses(lossesPercent, share(parts.stream().map(PricedPart::quantity)
                    .reduce(Quantity.ZERO, Quantity::plus), lossesPercent),
                    Money.ofQuotient(numerator.multiply(lossesPercent), denominator.multiply(HUNDRED)),
                    parts.stream().map(p -> new PricedPart(p.terms(), p.days(), share(p.quantity(), lossesPercent),
                            p.from(), p.to())).toList());
        }
        return new Priced(amount, parts, losses);
    }

    /** {@code quantity x percent / 100}, exact. */
    static Quantity share(Quantity quantity, BigDecimal percent) {
        BigInteger unscaled = percent.unscaledValue();
        BigInteger scale = BigInteger.TEN.pow(Math.max(0, percent.scale()));
        BigInteger factor = percent.scale() >= 0 ? unscaled : unscaled.multiply(BigInteger.TEN.pow(-percent.scale()));
        return Quantity.ofFraction(quantity.numerator().multiply(factor),
                quantity.denominator().multiply(scale).multiply(BigInteger.valueOf(100)));
    }
}
