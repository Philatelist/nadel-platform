package app.nadel.members;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The name and contacts of a member ({@link MemberDirectory#contacts}), for the staff reports that list who to call or
 * write to (feature 013, debtors). Personal data: never log it, never put it in an exception message or an audit
 * parameter.
 *
 * @param id         the member
 * @param lastName   the last name
 * @param firstName  the first name
 * @param middleName the patronymic; null when the member has none
 * @param phones     the phone numbers as stored (international format), in the stored order; empty when there are none
 * @param email      the e-mail address; null when there is none
 * @param archived   whether the member is archived
 */
public record MemberContacts(UUID id, String lastName, String firstName, @Nullable String middleName,
        List<String> phones, @Nullable String email, boolean archived) {

    public MemberContacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(firstName, "firstName");
        phones = phones == null ? List.of() : List.copyOf(phones);
    }

    /** "Last First Middle" (the patronymic only when there is one): how a report names an owner in full. */
    public String fullName() {
        return middleName == null || middleName.isBlank() ? shortName() : shortName() + " " + middleName;
    }

    /** "Last First", without the patronymic, as {@link MemberRef#shortName()}. */
    public String shortName() {
        return lastName + " " + firstName;
    }
}
