package app.nadel.members.internal;

import app.nadel.members.internal.MemberAccessState.State;
import java.time.Instant;
import java.util.UUID;
import nadel.contract.model.MemberAccessStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Switches a member's access off and points the access transitions that need Keycloak at
 * {@link MemberAccessListener} (feature 020): the disable endpoint and the archive revoke access, an e-mail change asks
 * for a new login. Runs in the caller's transaction, with the member locked by the caller; the events are id-only and
 * reach the listener through the outbox after the commit.
 */
@Component
class MemberAccessRevocation {

    private final MemberAccessState access;
    private final MemberInvitationRows invitations;
    private final ApplicationEventPublisher events;

    MemberAccessRevocation(MemberAccessState access, MemberInvitationRows invitations,
            ApplicationEventPublisher events) {
        this.access = access;
        this.invitations = invitations;
        this.events = events;
    }

    /** Whether the member has access or an open invitation: {@code ACTIVE} (a legacy link included) or {@code INVITED}. */
    boolean hasAccessOrInvitation(Member member) {
        MemberAccessStatus status = MemberAccessState.status(member, access.find(member.getId()));
        return status == MemberAccessStatus.ACTIVE || status == MemberAccessStatus.INVITED;
    }

    /**
     * Sets {@code DISABLED} with {@code disabled_at}, revokes the member's unused links and publishes
     * {@link MemberAccessRevoked}, when the member has access or an open invitation; otherwise does nothing. Answers
     * whether access was switched off.
     */
    boolean revoke(Member member, Instant at) {
        if (!hasAccessOrInvitation(member)) {
            return false;
        }
        UUID tenantId = member.getTenantId();
        access.set(tenantId, member.getId(), State.DISABLED, at);
        invitations.revokeUnused(member.getId(), at);
        events.publishEvent(new MemberAccessRevoked(tenantId, member.getId()));
        return true;
    }

    /**
     * The member's e-mail changed: when the member has access or an open invitation, sets {@code INVITED} (the new
     * address must be confirmed through a new link), revokes the member's unused links and publishes
     * {@link MemberLoginChanged}; otherwise does nothing. Answers whether the login is to change.
     */
    boolean loginChanged(Member member, Instant at) {
        if (!hasAccessOrInvitation(member)) {
            return false;
        }
        UUID tenantId = member.getTenantId();
        access.set(tenantId, member.getId(), State.INVITED, at);
        invitations.revokeUnused(member.getId(), at);
        events.publishEvent(new MemberLoginChanged(tenantId, member.getId()));
        return true;
    }
}
