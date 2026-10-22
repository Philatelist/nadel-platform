package app.nadel.meters;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A meter reading or a quantity of a resource: kWh or cubic metres, not money. Not negative, at most eleven integer
 * digits and at most three decimals, always held with scale 3 ({@code 8205} becomes {@code 8205.000}), which is the
 * {@code numeric(14,3)} of the database. Immutable.
 * <p>
 * A reading is exactly what the person typed: a value with a fourth decimal is rejected with an
 * {@link InvalidMeterValueException}, never cut. The wire format of the API reads digits with an optional fraction of
 * one to three digits and is written back with exactly three decimals ({@code "8205.000"}); clients trim the zeros for
 * display. Calculated values (interpolation, consumption) are {@link Quantity} until they are shown, and
 * {@link Quantity#toMeterValue()} is the one place that rounds (ADR 0014).
 */
public final class MeterValue implements Comparable<MeterValue> {

    /** The scale of every value (the {@code numeric(14,3)} of the database). */
    public static final int SCALE = 3;

    /** Zero, {@code 0.000}. */
    public static final MeterValue ZERO = new MeterValue(BigDecimal.ZERO.setScale(SCALE));

    private static final int MAX_INTEGER_DIGITS = 11;
    private static final Pattern WIRE_FORMAT = Pattern.compile(
            "^\\d{1," + MAX_INTEGER_DIGITS + "}(\\.\\d{1," + SCALE + "})?$");

    private final BigDecimal value;

    private MeterValue(BigDecimal value) {
        this.value = value;
    }

    /**
     * The value of a number, normalised to scale 3 without rounding. Trailing zeros beyond three places are ignored
     * ({@code 1.230000} is fine), a real fourth decimal is not.
     *
     * @throws InvalidMeterValueException when the number is negative or needs more than three decimals or eleven
     *                                    integer digits
     */
    public static MeterValue of(BigDecimal number) {
        Objects.requireNonNull(number, "number");
        if (number.signum() < 0) {
            throw new InvalidMeterValueException("A meter value is not negative");
        }
        BigDecimal stripped = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            throw new InvalidMeterValueException("A meter value has at most three decimals");
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidMeterValueException("A meter value has at most eleven integer digits");
        }
        return new MeterValue(stripped.setScale(SCALE)); // scale only grows here, so no rounding mode is needed
    }

    /**
     * Reads the wire format of the API: digits with an optional fraction of one to three digits ({@code "8205"},
     * {@code "8205.5"}, {@code "0.125"}). No sign, no exponent, no spaces, no decimal comma.
     *
     * @throws InvalidMeterValueException for anything else
     */
    public static MeterValue parse(String text) {
        if (text == null || !WIRE_FORMAT.matcher(text).matches()) {
            throw new InvalidMeterValueException(
                    "A meter value is a non-negative number with at most three decimals");
        }
        return of(new BigDecimal(text));
    }

    /** The value, always with scale 3. */
    public BigDecimal value() {
        return value;
    }

    @Override
    public int compareTo(MeterValue other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MeterValue meterValue && value.equals(meterValue.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The plain decimal text with three decimals: the wire format ({@code "8205.000"}). */
    @Override
    public String toString() {
        return value.toPlainString();
    }
}
