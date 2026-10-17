package app.nadel.members.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A phone number in international format ({@code +380671234567}). Created only through {@link #parse}, so a value
 * that exists is always normalised.
 * <p>
 * Spaces, dashes, dots and brackets are ignored. A Ukrainian number typed as {@code 0XXXXXXXXX} or
 * {@code 380XXXXXXXXX} becomes {@code +380XXXXXXXXX}; a number that already starts with {@code +} must have 8 to 15
 * digits and not start with 0. Anything else is invalid.
 */
record PhoneNumber(String value) {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()\\[\\]]");
    private static final Pattern NATIONAL = Pattern.compile("0\\d{9}");
    private static final Pattern UKRAINE = Pattern.compile("380\\d{9}");
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+[1-9]\\d{7,14}");

    /** @return the normalised number, or empty when the text is not a valid phone number */
    static Optional<PhoneNumber> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = SEPARATORS.matcher(raw).replaceAll("");
        if (NATIONAL.matcher(text).matches()) {
            return Optional.of(new PhoneNumber("+38" + text));
        }
        if (UKRAINE.matcher(text).matches()) {
            return Optional.of(new PhoneNumber("+" + text));
        }
        if (INTERNATIONAL.matcher(text).matches()) {
            return Optional.of(new PhoneNumber(text));
        }
        return Optional.empty();
    }
}
