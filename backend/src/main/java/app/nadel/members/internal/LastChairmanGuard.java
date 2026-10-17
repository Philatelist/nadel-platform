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
     * @throws ApiProblemException 409 {@code LAST_CHAIRMAN} when {@code memberId} is the only active chairman of the
     *                             cooperative; nothing happens for a member who is not an active chairman
     */
    void ensureNotLast(UUID tenantId, UUID memberId) {
        members.lockChairmen(tenantId);
        List<UUID> chairmen = members.findActiveChairmanIds(tenantId);
        if (chairmen.size() == 1 && chairmen.contains(memberId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.LAST_CHAIRMAN,
                    "The cooperative must keep at least one chairman.");
        }
    }
}
