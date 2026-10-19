package app.nadel.members;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only lookup of members for other modules, so they never touch the member tables. The caller has bound the
 * cooperative (a transaction of the tenant-aware transaction manager); members of other cooperatives are invisible.
 */
public interface MemberDirectory {

    /** The most ids {@link #idsMatchingName} returns. */
    int MAX_NAME_MATCHES = 200;

    /** The given members, archived ones included. Ids that do not exist in the cooperative are absent from the map. */
    Map<UUID, MemberRef> find(Collection<UUID> ids);

    /**
     * Ids of the members whose last, first or middle name contains {@code text}, ignoring case, archived members
     * included. The text is matched literally ({@code %} and {@code _} are ordinary characters). A blank text matches
     * nobody. At most {@value #MAX_NAME_MATCHES} ids are returned, so a very short text cannot flood a query.
     */
    List<UUID> idsMatchingName(String text);
}
