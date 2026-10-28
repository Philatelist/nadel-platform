package app.nadel.members.internal;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a member search query can stand for. Contacts are sealed, so they are found only by exact blind-index matches:
 * <ul>
 * <li>{@code text}: the trimmed query, matched as a case-insensitive substring of the last, first and middle name;</li>
 * <li>{@code email}: the normalised query when it contains {@code @} (the whole address, case ignored);</li>
 * <li>{@code last4}: the query when it is exactly four digits;</li>
 * <li>{@code phone}: the international form when the query parses as a {@link PhoneNumber}.</li>
 * </ul>
 * A part of an e-mail or digits from the middle of a phone find nothing.
 */
record MemberSearchTerms(String text, @Nullable String email, @Nullable String phone, @Nullable String last4) {

    private static final Pattern FOUR_DIGITS = Pattern.compile("\\d{4}");

    static MemberSearchTerms parse(@Nullable String query) {
        String text = query == null ? "" : query.strip();
        String email = text.contains("@") ? PhoneIndexValues.normalisedEmail(text) : null;
        String last4 = FOUR_DIGITS.matcher(text).matches() ? text : null;
        String phone = PhoneNumber.parse(text).map(PhoneNumber::value).orElse(null);
        return new MemberSearchTerms(text, email, phone, last4);
    }
}
