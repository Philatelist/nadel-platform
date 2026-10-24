package app.nadel.meters;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact amount of a resource: a fraction of two integers, always reduced, with a positive denominator. Immutable.
 * <p>
 * Readings have three decimals, but what is calculated from them does not: a reading interpolated for a date between
 * two readings is {@code v0 + (v1 - v0) * k / N}, which is a repeating decimal for most day counts. A {@code Quantity}
 * keeps that value exactly, so consumption over any range, the sum over a chain of meters and the slices of a moved
 * meter add up to the total with no drift. Nothing here rounds except {@link #toMeterValue()}, which rounds HALF_UP to
 * three decimals for display and is the only rounding of the meters module (ADR 0014). A consumer that needs the exact
 * value, such as the electricity charge of feature 011, reads {@link #numerator()} and {@link #denominator()}.
 */
public final class Quantity implements Comparable<Quantity> {

    /** Zero. */
    public static final Quantity ZERO = new Quantity(BigInteger.ZERO, BigInteger.ONE);

    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private Quantity(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /** The exact quantity of a reading. */
    public static Quantity of(MeterValue value) {
        return ofFraction(Objects.requireNonNull(value, "value").value().unscaledValue(), THOUSAND);
    }

    /**
     * {@code numerator / denominator}, reduced; a negative denominator moves its sign to the numerator.
     *
     * @throws ArithmeticException when the denominator is zero
     */
    public static Quantity ofFraction(BigInteger numerator, BigInteger denominator) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() == 0) {
            throw new ArithmeticException("The denominator of a quantity is not zero");
        }
        BigInteger n = denominator.signum() < 0 ? numerator.negate() : numerator;
        BigInteger d = denominator.abs();
        BigInteger gcd = n.gcd(d); // never zero: d is not zero
        return new Quantity(n.divide(gcd), d.divide(gcd));
    }

    public Quantity plus(Quantity other) {
        return ofFraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Quantity minus(Quantity other) {
        return ofFraction(numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    /** This quantity multiplied by a whole number (a number of days, a factor). */
    public Quantity times(long factor) {
        return ofFraction(numerator.multiply(BigInteger.valueOf(factor)), denominator);
    }

    /**
     * This quantity divided by a whole number, exactly.
     *
     * @throws ArithmeticException when the divisor is zero
     */
    public Quantity dividedBy(long divisor) {
        return ofFraction(numerator, denominator.multiply(BigInteger.valueOf(divisor)));
    }

    /** -1, 0 or 1 as this quantity is negative, zero or positive. */
    public int signum() {
        return numerator.signum();
    }

    /** The numerator of the reduced fraction; it carries the sign. */
    public BigInteger numerator() {
        return numerator;
    }

    /** The denominator of the reduced fraction; always positive. */
    public BigInteger denominator() {
        return denominator;
    }

    @Override
    public int compareTo(Quantity other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    /**
     * The value for display with its sign: rounded HALF_UP to three decimals (scale 3). This is the one place in the
     * module that rounds a quantity; it is for showing a value, never for calculating with it. A difference such as
     * the losses of a node or the kWh of a reversal can be negative, which {@link #toMeterValue()} refuses; a report
     * shows it with this form (feature 013).
     */
    public BigDecimal toDecimal() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MeterValue.SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The value for display as a meter value: {@link #toDecimal()}, which must not be negative.
     *
     * @throws IllegalStateException when the quantity is negative (only a difference can be, and no reading is)
     */
    public MeterValue toMeterValue() {
        BigDecimal rounded = toDecimal();
        if (rounded.signum() < 0) {
            throw new IllegalStateException("A negative quantity is not a meter value");
        }
        return MeterValue.of(rounded);
    }

    /** Two quantities are equal when they are the same number: the fraction is always reduced. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Quantity quantity && numerator.equals(quantity.numerator)
                && denominator.equals(quantity.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    /** The exact fraction ({@code "3425/3"}) or the whole number ({@code "150"}); for logs and tests, not for display. */
    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
