package app.nadel.members.internal;

import java.util.UUID;

/**
 * The member's access to the system was switched off (feature 020): the board disabled it or archived the member.
 * Published in the transaction that stored {@code DISABLED}; {@link MemberAccessListener} then turns the Keycloak
 * account off and ends its sessions. Ids only: the outbox never holds an e-mail or a link. Internal to the members
 * module.
 */
record MemberAccessRevoked(UUID tenantId, UUID memberId) {
}
