package app.nadel.money;

/** A rate that is negative, has more than four decimals or more than ten integer digits, or is not a number. */
public final class InvalidRateException extends IllegalArgumentException {

    InvalidRateException(String message) {
        super(message);
    }
}
