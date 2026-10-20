package app.nadel.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount in hryvnias with exactly two decimal places (kopecks). Immutable.
 * <p>
 * This is the one place in the platform that rounds money: {@link #of(BigDecimal)} and {@link #ofQuotient} round
 * HALF_UP to two places, and nothing else does (money-rules section 1). Intermediate results of a calculation stay
 * exact {@link BigDecimal}s and become money once, at the end.
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
