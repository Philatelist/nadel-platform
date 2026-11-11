package app.nadel.members.internal;

import java.util.UUID;

/**
 * The e-mail (the login) of a member with access or an open invitation changed (feature 020): the transaction that
 * changed it stored {@code INVITED} and revoked the member's links. {@link MemberAccessListener} then moves the Keycloak
 * account to the new login (not verified) and sends a new link to the new address. Ids only: the new e-mail and the
 * link never enter the outbox. Internal to the members module.
 */
record MemberLoginChanged(UUID tenantId, UUID memberId) {
}
