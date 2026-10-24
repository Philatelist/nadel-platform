package app.nadel.settings.internal;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A valid Ukrainian IBAN: {@code UA}, two check digits and 25 digits (the bank code and the account), 29 characters
 * without spaces, whose ISO 13616 mod-97 check is 1. Kept normalised: no spaces, upper case.
 *
 * @param value the normalised IBAN, for example {@code UA213223130000026007233566001}
 */
record Iban(String value) {

    /** Only ASCII digits: {@code Character.isDigit} would also accept the digits of other scripts. */
    private static final Pattern UKRAINIAN = Pattern.compile("^UA[0-9]{27}$");
    /** Spaces, tabs and the no-break space that a copy from a bank statement brings along. */
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    Iban {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Not a valid Ukrainian IBAN.");
        }
    }

    /**
     * The IBAN typed by a person, without spaces and in upper case; empty when the text is not a valid Ukrainian
     * IBAN.
     */
    static Optional<Iban> parse(String text) {
        String normalised = normalise(text);
        return isValid(normalised) ? Optional.of(new Iban(normalised)) : Optional.empty();
    }

    /** The text without any space and in upper case. */
    static String normalise(String text) {
        return SPACES.matcher(text).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** Whether the normalised text is {@code UA} and 27 digits whose mod-97 check (ISO 7064 MOD 97-10) is 1. */
    static boolean isValid(String normalised) {
        return UKRAINIAN.matcher(normalised).matches() && mod97(normalised) == 1;
    }

    /**
     * The remainder of the IBAN as a number modulo 97: the first four characters are moved to the end and each letter
     * becomes two digits ({@code A} = 10 ... {@code Z} = 35), computed digit by digit so no big number is needed.
     */
    private static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
            } else {
                remainder = (remainder * 10 + (c - '0')) % 97;
            }
        }
        return remainder;
    }

    @Override
    public String toString() {
        return value;
    }
}
