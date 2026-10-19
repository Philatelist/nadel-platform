package app.nadel.members;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A member as other modules see it: the parts of the name and whether the member is archived. Personal data: never
 * log it.
 */
public record MemberRef(UUID id, String lastName, String firstName, @Nullable String middleName, boolean archived) {

    /** "Last First", without the patronymic: how members are named in lists of owners. */
    public String shortName() {
        return lastName + " " + firstName;
    }
}
