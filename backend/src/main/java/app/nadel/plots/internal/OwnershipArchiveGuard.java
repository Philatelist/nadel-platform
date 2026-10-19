package app.nadel.plots.internal;

import app.nadel.members.MemberArchiveGuard;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A member who currently owns a plot cannot be archived: the owner of the plot must be changed first.
 * <p>
 * Runs inside the archive transaction, after the archive has locked the member row ({@code FOR UPDATE}). A concurrent
 * change of owner that adds this member holds the plot lock and then asks for the member's {@code FOR SHARE} lock, so
 * the two never interleave: if the change committed first, this check sees its rows (the statement reads a fresh
 * snapshot); if the archive committed first, the change finds the member archived and is refused with
 * {@code MEMBER_ARCHIVED}. The guard takes no locks itself, so it adds no lock-order edge.
 */
@Component
class OwnershipArchiveGuard implements MemberArchiveGuard {

    private final OwnershipRepository ownerships;

    OwnershipArchiveGuard(OwnershipRepository ownerships) {
        this.ownerships = ownerships;
    }

    @Override
    public void ensureCanArchive(UUID memberId) {
        if (ownerships.existsCurrentByMemberId(memberId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.MEMBER_OWNS_PLOT,
                    "This member owns a plot; change the owner first.");
        }
    }
}
