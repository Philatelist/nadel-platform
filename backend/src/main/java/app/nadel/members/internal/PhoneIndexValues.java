package app.nadel.members.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The normal forms that blind indexes are computed over, shared by the Java migration 22.1, the demo contacts callback
 * and the application, so a value indexed by one is found by the others:
 * <ul>
 * <li>e-mail: trimmed and lower-case ({@link Locale#ROOT}), as {@link MemberData} stores it;</li>
 * <li>phone: the international form of {@link PhoneNumber} (a stored value that does not parse is indexed as stored,
 * trimmed);</li>
 * <li>last 4: the last four digits of the normalised phone.</li>
 * </ul>
 */
final class PhoneIndexValues {

    private PhoneIndexValues() {
    }

    /** One row of {@code member_phone_index} before hashing: the normalised phone and its last four digits. */
    record Entry(String phone, String last4) {
    }

    static @Nullable String normalisedEmail(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static String normalisedPhone(String phone) {
        return PhoneNumber.parse(phone).map(PhoneNumber::value).orElse(phone.strip());
    }

    /** The last four digits of a normalised phone, or null when it has fewer than four digits. */
    static @Nullable String last4(String normalisedPhone) {
        String digits = normalisedPhone.replaceAll("[^0-9]", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    /**
     * The index entries of a member's phones: one per distinct normalised phone; blank values and phones with fewer
     * than four digits are not indexed.
     */
    static List<Entry> entries(Collection<String> phones) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String phone : phones) {
            if (phone != null && !phone.isBlank()) {
                normalised.add(normalisedPhone(phone));
            }
        }
        return normalised.stream()
                .filter(phone -> last4(phone) != null)
                .map(phone -> new Entry(phone, last4(phone)))
                .toList();
    }
}
