package app.nadel.money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tariff rate: not negative, at most ten integer digits and at most four decimals, always held with scale 4
 * ({@code 4.32} becomes {@code 4.3200}). Immutable.
 * <p>
 * A rate is not money: it is never rounded, not even to four places. A value with a fifth decimal is rejected, never
 * cut, so what is stored is exactly what the chairman entered. A rate becomes money only through
 * {@link Money#ofQuotient} at the end of a calculation.
 */
public final class Rate {

    /** The scale of every rate (the {@code numeric(14,4)} of the database). */
    public static final int SCALE = 4;

    private static final int MAX_INTEGER_DIGITS = 10;
    private static final Pattern WIRE_FORMAT = Pattern.compile("^\\d{1," + MAX_INTEGER_DIGITS + "}(\\.\\d{1," + SCALE + "})?$");

    private final BigDecimal value;

    private Rate(BigDecimal value) {
        this.value = value;
    }

    /**
     * The rate of a number, normalised to scale 4 without rounding. Trailing zeros beyond four places are ignored
     * ({@code 1.230000} is fine), a real fifth decimal is not.
     *
     * @throws InvalidRateException when the number is negative or needs more than four decimals or ten integer digits
     */
    public static Rate of(BigDecimal number) {
        Objects.requireNonNull(number, "number");
        if (number.signum() < 0) {
            throw new InvalidRateException("A rate is not negative");
        }
        BigDecimal stripped = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            throw new InvalidRateException("A rate has at most four decimals");
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidRateException("A rate has at most ten integer digits");
        }
        return new Rate(stripped.setScale(SCALE)); // scale only grows here, so no rounding mode is needed
    }

    /**
     * Reads the wire format of the API: digits with an optional fraction of one to four digits ({@code "4.32"},
     * {@code "50"}). No sign, no exponent, no spaces.
     *
     * @throws InvalidRateException for anything else
     */
    public static Rate parse(String text) {
        if (text == null || !WIRE_FORMAT.matcher(text).matches()) {
            throw new InvalidRateException("A rate is a non-negative number with at most four decimals");
        }
        return of(new BigDecimal(text));
    }

    /** The rate, always with scale 4. */
    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rate rate && value.equals(rate.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The plain decimal text with four decimals: the wire format ("4.3200"). */
    @Override
    public String toString() {
        return value.toPlainString();
    }
}
