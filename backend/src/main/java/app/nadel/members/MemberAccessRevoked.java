package app.nadel.members;

import java.util.Objects;
import java.util.UUID;

/**
 * The member's access to the system was switched off (feature 020): the board disabled it or archived the member.
 * Published in the transaction that stored {@code DISABLED}; the members module then turns the Keycloak account off and
 * ends its sessions, and {@code notifications} deletes the member's push devices (feature 021 §2.2: leaving the
 * cooperative removes all of them). Ids only: the outbox never holds an e-mail or a link. A listener binds
 * {@code tenantId} with {@code TenantContext.runAs} before it opens its transaction (ADR 0007) and must be idempotent.
 *
 * @param tenantId the cooperative
 * @param memberId the member whose access was switched off
 */
public record MemberAccessRevoked(UUID tenantId, UUID memberId) {

    public MemberAccessRevoked {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(memberId, "memberId");
    }
}
