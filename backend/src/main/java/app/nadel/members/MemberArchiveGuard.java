package app.nadel.members;

import java.util.UUID;

/**
 * A veto on archiving a member, implemented by other modules that hold data about members (for example {@code plots},
 * which refuses a member who currently owns a plot). Every guard bean is called by the archive command of this module.
 * <p>
 * Called inside the archive transaction, after the member row has been locked ({@code FOR UPDATE}) and after the
 * last-chairman check, and only when the member is not archived yet. To veto, throw an
 * {@link app.nadel.problem.ApiProblemException} with status 409 and a stable code; the transaction rolls back and
 * nothing is archived. Implementations must only read, must not call back into the {@code members} module, and must
 * not take locks that are ordered before a member lock (a plot lock, for instance, is taken before member locks by
 * the writers that need both, so a guard must not take one).
 */
public interface MemberArchiveGuard {

    /**
     * @param memberId the member about to be archived; the cooperative is already bound to the transaction
     * @throws app.nadel.problem.ApiProblemException 409 to refuse the archive
     */
    void ensureCanArchive(UUID memberId);
}
