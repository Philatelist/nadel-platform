package app.nadel.plots.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An exact share of a plot, a fraction in lowest terms ("2/4" is "1/2"). Never a decimal, so "1/3 + 1/3 + 1/3" is
 * exactly one whole.
 * <p>
 * A share that a person owns is built with {@link #parse} or {@link #of}, which require
 * {@code 1 <= numerator <= denominator <= 1000}. {@link #plus} gives a sum, which may exceed one whole, so the
 * constructor itself only reduces the fraction.
 */
record Share(int numerator, int denominator) implements Comparable<Share> {

    static final int MAX_DENOMINATOR = 1000;

    static final Share WHOLE = new Share(1, 1);

    private static final Pattern FORMAT = Pattern.compile("^([1-9][0-9]{0,3})/([1-9][0-9]{0,3})$");

    Share {
        if (numerator < 1 || denominator < 1) {
            throw new InvalidShareException("A share needs a positive numerator and denominator.");
        }
        int gcd = gcd(numerator, denominator);
        numerator /= gcd;
        denominator /= gcd;
    }

    /** Parses "n/d" (no spaces, no signs, no decimals) and checks the bounds as written. */
    static Share parse(String text) {
        Matcher matcher = text == null ? null : FORMAT.matcher(text);
        if (matcher == null || !matcher.matches()) {
            throw new InvalidShareException("A share must be written as n/d, for example 1/2.");
        }
        return of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /** A share of one plot: {@code 1 <= numerator <= denominator <= 1000}, checked as given, then reduced. */
    static Share of(int numerator, int denominator) {
        if (numerator < 1 || denominator > MAX_DENOMINATOR || numerator > denominator) {
            throw new InvalidShareException("A share needs 1 <= numerator <= denominator <= " + MAX_DENOMINATOR + ".");
        }
        return new Share(numerator, denominator);
    }

    /**
     * The exact sum, in lowest terms; it may be more than one whole.
     *
     * @throws ArithmeticException when the sum does not fit the {@code int} fraction (the sum of many unrelated
     *         denominators); such a sum is never one whole
     */
    Share plus(Share other) {
        long n = Math.addExact(Math.multiplyExact((long) numerator, other.denominator),
                Math.multiplyExact((long) other.numerator, denominator));
        long d = Math.multiplyExact((long) denominator, other.denominator);
        long gcd = gcd(n, d);
        return new Share(Math.toIntExact(n / gcd), Math.toIntExact(d / gcd));
    }

    boolean isWhole() {
        return numerator == denominator;
    }

    /** Compares by value with cross-multiplication, so 1/3 &lt; 1/2 &lt; 2/3 without any rounding. */
    @Override
    public int compareTo(Share other) {
        return Long.compare((long) numerator * other.denominator, (long) other.numerator * denominator);
    }

    /** "n/d", the form used by the API. */
    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }

    private static int gcd(int a, int b) {
        return (int) gcd((long) a, (long) b);
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long rest = a % b;
            a = b;
            b = rest;
        }
        return a;
    }
}
