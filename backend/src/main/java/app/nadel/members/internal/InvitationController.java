package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import nadel.contract.api.MemberAccessApi;
import nadel.contract.model.MemberAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's access to the system (feature 020): invite, send the invitation again, turn access off. Requires
 * {@code MEMBERS_VIEW} and {@code MEMBERS_INVITE}; the cooperative is the caller's (RLS hides other cooperatives'
 * members, which are 404). Invite and resend are limited by the sent invitations of the member and of the client
 * address ({@link InvitationRateLimiter}).
 */
@RestController
@RequestMapping("/api/v1")
class InvitationController implements MemberAccessApi {

    private final CurrentUser currentUser;
    private final MemberInvitations invitations;
    private final InvitationRateLimiter limiter;

    InvitationController(CurrentUser currentUser, MemberInvitations invitations, InvitationRateLimiter limiter) {
        this.currentUser = currentUser;
        this.invitations = invitations;
        this.limiter = limiter;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> inviteMember(UUID id) {
        AccessProfile actor = currentUser.profile();
        return ResponseEntity.ok(limitedSend(actor, id, () -> invitations.invite(id, actor)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> resendMemberInvitation(UUID id) {
        AccessProfile actor = currentUser.profile();
        return ResponseEntity.ok(limitedSend(actor, id, () -> invitations.resend(id, actor)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> disableMemberAccess(UUID id) {
        return ResponseEntity.ok(invitations.disable(id, currentUser.profile()));
    }

    /**
     * A sent invitation counts for the member and the client address ({@link InvitationRateLimiter}); over a limit 429
     * {@code TOO_MANY_REQUESTS} before anything is sent. One that is refused or not sent is not counted.
     */
    private MemberAccess limitedSend(AccessProfile actor, UUID memberId, Supplier<MemberAccess> send) {
        String address = InvitationRateLimiter.clientAddress();
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        limiter.ensureSendAllowed(address, tenantId, memberId);
        MemberAccess access = send.get();
        limiter.recordSend(address, tenantId, memberId);
        return access;
    }
}
