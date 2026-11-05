package app.nadel.members.internal;

import nadel.contract.api.PublicInvitationsApi;
import nadel.contract.model.InvitationAcceptInput;
import nadel.contract.model.InvitationInfo;
import nadel.contract.model.InvitationTokenInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public invitation endpoints (feature 020, {@code security: []}, {@code /api/v1/public/**} is open in
 * {@code identity.internal.SecurityConfiguration}): the set-password page inspects a link and accepts it with the new
 * password. The token travels in the body, never in the URL. The token and the password are secrets: neither they nor
 * the request bodies are ever logged (the generated input types print them as {@code *}).
 */
@RestController
@RequestMapping("/api/v1")
class PublicInvitationController implements PublicInvitationsApi {

    private final MemberInvitations invitations;

    PublicInvitationController(MemberInvitations invitations) {
        this.invitations = invitations;
    }

    @Override
    public ResponseEntity<InvitationInfo> inspectInvitation(InvitationTokenInput invitationTokenInput) {
        return ResponseEntity.ok(invitations.inspect(invitationTokenInput.getToken()));
    }

    @Override
    public ResponseEntity<Void> acceptInvitation(InvitationAcceptInput invitationAcceptInput) {
        invitations.accept(invitationAcceptInput.getToken(), invitationAcceptInput.getPassword());
        return ResponseEntity.noContent().build();
    }
}
