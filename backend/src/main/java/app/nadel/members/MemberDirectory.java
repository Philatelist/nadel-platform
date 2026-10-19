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

    /**
     * Pins the given members for the rest of the caller's transaction: takes a shared row lock ({@code FOR SHARE}), so
     * a concurrent archiving of one of them waits until the caller commits (and the other way round). Returns the
     * members that exist in the cooperative, archived ones included (they are flagged in {@link MemberRef#archived()},
     * so the caller can refuse them); unknown ids and ids of other cooperatives are absent from the map. Must run in
     * a transaction. Locks are taken in id order, so two callers cannot deadlock on each other.
     */
    Map<UUID, MemberRef> lockActive(Collection<UUID> ids);
}
