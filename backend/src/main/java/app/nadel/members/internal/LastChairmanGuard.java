package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A cooperative must always keep at least one active chairman. Call {@link #ensureNotLast} before anything that
 * makes a member stop being an active chairman: archiving them or taking the Chairman role away.
 * <p>
 * Must run inside the transaction that makes the change: the per-cooperative advisory lock it takes lives until that
 * transaction ends, which is what makes two concurrent removals safe.
 */
@Component
class LastChairmanGuard {

    private final MemberRepository members;

    LastChairmanGuard(MemberRepository members) {
        this.members = members;
    }

    /**
     * Takes the cooperative's chairman lock without checking anything. A writer that also locks a member row takes this
     * first, so that every path orders the locks the same way (chairman lock, then member row): a role change holds the
     * chairman lock and then needs a share lock on the member row for the new role link, and would otherwise deadlock
     * with an archive that holds the row. Taking it again inside {@link #ensureNotLast} is harmless.
     */
    void lock(UUID tenantId) {
        members.lockChairmen(tenantId);
    }

    /**
     * @throws ApiProblemException 409 {@code LAST_CHAIRMAN} when {@code memberId} is the only active chairman of the
     *                             cooperative; nothing happens for a member who is not an active chairman
     */
    void ensureNotLast(UUID tenantId, UUID memberId) {
        lock(tenantId);
        List<UUID> chairmen = members.findActiveChairmanIds(tenantId);
        if (chairmen.size() == 1 && chairmen.contains(memberId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.LAST_CHAIRMAN,
                    "The cooperative must keep at least one chairman.");
        }
    }
}
