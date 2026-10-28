package app.nadel.members.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The contacts of a member in the clear, opened by {@link MemberCrypto} for one request and never stored this way.
 * Personal data: never log it.
 *
 * @param phones international format, in the stored order; empty when there are none
 */
record OpenedContacts(
        List<String> phones,
        @Nullable String email,
        @Nullable String postalAddress,
        @Nullable String telegramUsername,
        @Nullable String viberPhone,
        @Nullable String notes) {

    /** The contacts of a member that does not exist yet. */
    static final OpenedContacts NONE = new OpenedContacts(List.of(), null, null, null, null, null);

    OpenedContacts {
        phones = List.copyOf(phones);
    }
}
