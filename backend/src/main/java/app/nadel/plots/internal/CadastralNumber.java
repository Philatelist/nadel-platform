package app.nadel.plots.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A cadastral number of the form {@code 1234567890:12:345:6789} (10, 2, 3 and 4 ASCII digits). Created only through
 * {@link #parse}, so a value that exists always has the right shape. The text is taken as given apart from
 * surrounding whitespace; the number is an identifier, not a calculation.
 */
record CadastralNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{10}:[0-9]{2}:[0-9]{3}:[0-9]{4}");

    /** @return the number, or empty when the text is not in the cadastral format */
    static Optional<CadastralNumber> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        return FORMAT.matcher(text).matches() ? Optional.of(new CadastralNumber(text)) : Optional.empty();
    }
}
