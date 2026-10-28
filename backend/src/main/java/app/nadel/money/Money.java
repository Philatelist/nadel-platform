package app.nadel.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount in hryvnias with exactly two decimal places (kopecks). Immutable.
 * <p>
 * This is the one place in the platform that rounds money: {@link #of(BigDecimal)} and {@link #ofQuotient} round
 * HALF_UP to two places, {@link #allocate} rounds the parts of a proportional split down and hands out the leftover
 * kopecks by the largest remainder, and nothing else rounds (money-rules section 1). Intermediate results of a
 * calculation stay exact {@link BigDecimal}s and become money once, at the end.
 */
public final class Money {

    /** The scale of every amount. */
    public static final int SCALE = 2;

    /** The rounding of every amount. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Zero hryvnias, {@code 0.00}. */
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

    private static final Pattern WIRE_FORMAT = Pattern.compile("^-?\\d+\\.\\d{2}$");

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    /** The amount rounded HALF_UP to two decimal places. */
    public static Money of(BigDecimal amount) {
        return new Money(Objects.requireNonNull(amount, "amount").setScale(SCALE, ROUNDING));
    }

    /**
     * {@code numerator / denominator} as one exact division rounded HALF_UP to two decimal places: the single rounding
     * of a calculation that was kept exact until now.
     *
     * @throws ArithmeticException when the denominator is zero
     */
    public static Money ofQuotient(BigDecimal numerator, BigDecimal denominator) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        return new Money(numerator.divide(denominator, SCALE, ROUNDING));
    }

    /**
     * Reads the wire format of the API: an optional minus sign, digits, a point and exactly two decimals ("1250.50").
     *
     * @throws IllegalArgumentException for anything else, including a missing or a longer fraction
     */
    public static Money parse(String text) {
        if (text == null || !WIRE_FORMAT.matcher(text).matches()) {
            throw new IllegalArgumentException("An amount has the form 1250.50 (exactly two decimals)");
        }
        return new Money(new BigDecimal(text));
    }

    public Money plus(Money other) {
        return new Money(value.add(other.value));
    }

    /** {@code this - other}, exact (both have two decimals, so nothing is rounded). */
    public Money minus(Money other) {
        return new Money(value.subtract(other.value));
    }

    /** The same amount with the opposite sign: the amount of a reversal. {@code 0.00} stays {@code 0.00}. */
    public Money negate() {
        return new Money(value.negate());
    }

    /**
     * This amount split in proportion to {@code weights}, one part per weight in the same order, the parts adding up
     * to this amount exactly: the proportional split of a payment over several plots by their debts (feature 015
     * §2.2). Each part is first its exact share rounded <b>down</b> to the kopeck; the kopecks left over (fewer than
     * the number of weights) go one each to the parts with the largest remainders of that rounding, ties to the
     * earlier weight. This is the only rounding of a proportional split (ADR 0013), so it lives here.
     * <p>
     * When every weight is zero there is nothing to be proportional to, and the whole amount is the first part (the
     * others {@code 0.00}). 100.00 over 100.00, 100.00, 100.00 is 33.34, 33.33, 33.33; 200.00 over 300.00, 100.00 is
     * 150.00, 50.00.
     *
     * @throws IllegalArgumentException when this amount or a weight is negative, or there is no weight
     */
    public List<Money> allocate(List<Money> weights) {
        Objects.requireNonNull(weights, "weights");
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("An amount is allocated over at least one weight");
        }
        if (signum() < 0) {
            throw new IllegalArgumentException("Only an amount that is not negative is allocated");
        }
        BigInteger total = value.unscaledValue();
        BigInteger sum = BigInteger.ZERO;
        for (Money weight : weights) {
            if (Objects.requireNonNull(weight, "weight").signum() < 0) {
                throw new IllegalArgumentException("A weight is not negative");
            }
            sum = sum.add(weight.value.unscaledValue());
        }
        int size = weights.size();
        BigInteger[] cents = new BigInteger[size];
        if (sum.signum() == 0) {
            Arrays.fill(cents, BigInteger.ZERO);
            cents[0] = total;
            return parts(cents);
        }
        BigInteger[] remainders = new BigInteger[size];
        BigInteger left = total;
        for (int i = 0; i < size; i++) {
            BigInteger[] division = total.multiply(weights.get(i).value.unscaledValue()).divideAndRemainder(sum);
            cents[i] = division[0];
            remainders[i] = division[1];
            left = left.subtract(division[0]);
        }
        Integer[] byRemainder = new Integer[size];
        Arrays.setAll(byRemainder, i -> i);
        // largest remainder first; the sort is stable, so equal remainders keep the order of the weights
        Arrays.sort(byRemainder, Comparator.comparing((Integer i) -> remainders[i]).reversed());
        // every part was rounded down by less than a kopeck, so fewer kopecks than parts are left
        int leftover = left.intValueExact();
        for (int k = 0; k < leftover; k++) {
            cents[byRemainder[k]] = cents[byRemainder[k]].add(BigInteger.ONE);
        }
        return parts(cents);
    }

    private static List<Money> parts(BigInteger[] cents) {
        List<Money> parts = new ArrayList<>(cents.length);
        for (BigInteger part : cents) {
            parts.add(new Money(new BigDecimal(part, SCALE)));
        }
        return List.copyOf(parts);
    }

    /** -1, 0 or 1 as the amount is negative, zero or positive. */
    public int signum() {
        return value.signum();
    }

    /** The amount, always with scale 2. */
    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && value.equals(money.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The plain decimal text, never in exponent notation: the wire format ("1250.50"). */
    @Override
    public String toString() {
        return value.toPlainString();
    }
}
