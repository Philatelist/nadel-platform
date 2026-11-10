package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.util.function.Supplier;
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
 * <p>
 * The limit counts failed links only ({@link InvitationRateLimiter}): an address that used up its failed links is 429
 * {@code TOO_MANY_REQUESTS} before anything else runs, and each 404 {@code INVITATION_INVALID} is counted for the
 * address. A usable link can be opened any number of times.
 */
@RestController
@RequestMapping("/api/v1")
class PublicInvitationController implements PublicInvitationsApi {

    private final MemberInvitations invitations;
    private final InvitationRateLimiter limiter;

    PublicInvitationController(MemberInvitations invitations, InvitationRateLimiter limiter) {
        this.invitations = invitations;
        this.limiter = limiter;
    }

    @Override
    public ResponseEntity<InvitationInfo> inspectInvitation(InvitationTokenInput invitationTokenInput) {
        return limited(() -> ResponseEntity.ok(invitations.inspect(invitationTokenInput.getToken())));
    }

    @Override
    public ResponseEntity<Void> acceptInvitation(InvitationAcceptInput invitationAcceptInput) {
        return limited(() -> {
            invitations.accept(invitationAcceptInput.getToken(), invitationAcceptInput.getPassword());
            return ResponseEntity.noContent().build();
        });
    }

    private <T> T limited(Supplier<T> call) {
        String address = InvitationRateLimiter.clientAddress();
        limiter.ensureNotBlocked(address);
        try {
            return call.get();
        } catch (ApiProblemException e) {
            if (ProblemCodes.INVITATION_INVALID.equals(e.code())) {
                limiter.recordFailedLink(address);
            }
            throw e;
        }
    }
}
