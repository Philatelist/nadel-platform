package app.nadel.meters;

/** A meter value that is negative, has more than three decimals or eleven integer digits, or is not a number. */
public final class InvalidMeterValueException extends IllegalArgumentException {

    InvalidMeterValueException(String message) {
        super(message);
    }
}
