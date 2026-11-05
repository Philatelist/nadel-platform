package app.nadel.members.internal;

import app.nadel.identity.CurrentUser;
import java.util.UUID;
import nadel.contract.api.MemberAccessApi;
import nadel.contract.model.MemberAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's access to the system (feature 020): invite, send the invitation again, turn access off. Requires
 * {@code MEMBERS_VIEW} and {@code MEMBERS_INVITE}; the cooperative is the caller's (RLS hides other cooperatives'
 * members, which are 404).
 *
 * <p>TEMPORARY STUB (020 slice 6): resend and disable answer 501 until slice 6 implements them.
 */
@RestController
@RequestMapping("/api/v1")
class InvitationController implements MemberAccessApi {

    private final CurrentUser currentUser;
    private final MemberInvitations invitations;

    InvitationController(CurrentUser currentUser, MemberInvitations invitations) {
        this.currentUser = currentUser;
        this.invitations = invitations;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> inviteMember(UUID id) {
        return ResponseEntity.ok(invitations.invite(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> resendMemberInvitation(UUID id) {
        // TEMPORARY STUB: implemented in slice 6.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW') and hasAuthority('PERM_MEMBERS_INVITE')")
    public ResponseEntity<MemberAccess> disableMemberAccess(UUID id) {
        // TEMPORARY STUB: implemented in slice 6.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
