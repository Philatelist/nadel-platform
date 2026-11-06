package app.nadel.members.internal;

import app.nadel.members.internal.InvitationRateLimiter.Endpoint;
import nadel.contract.api.PublicInvitationsApi;
import nadel.contract.model.InvitationAcceptInput;
import nadel.contract.model.InvitationInfo;
import nadel.contract.model.InvitationTokenInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Public invitation endpoints (feature 020, {@code security: []}, {@code /api/v1/public/**} is open in
 * {@code identity.internal.SecurityConfiguration}): the set-password page inspects a link and accepts it with the new
 * password. The token travels in the body, never in the URL. The token and the password are secrets: neither they nor
 * the request bodies are ever logged (the generated input types print them as {@code *}).
 * <p>
 * Each request is counted first by the client address ({@code request.getRemoteAddr()}, which behind the proxy is the
 * client through {@code server.forward-headers-strategy}), then, when the token belongs to a link, by the link's
 * e-mail ({@link InvitationRateLimiter}); over a limit the answer is 429 {@code TOO_MANY_REQUESTS} and nothing else
 * runs.
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
        limit(Endpoint.INSPECT, invitationTokenInput.getToken());
        return ResponseEntity.ok(invitations.inspect(invitationTokenInput.getToken()));
    }

    @Override
    public ResponseEntity<Void> acceptInvitation(InvitationAcceptInput invitationAcceptInput) {
        limit(Endpoint.ACCEPT, invitationAcceptInput.getToken());
        invitations.accept(invitationAcceptInput.getToken(), invitationAcceptInput.getPassword());
        return ResponseEntity.noContent().build();
    }

    private void limit(Endpoint endpoint, String token) {
        // read per call, not injected: the command profile runs without a web server (FirstChairman)
        String address = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest()
                .getRemoteAddr();
        limiter.acquireForAddress(endpoint, address);
        invitations.rateLimitKey(token).ifPresent(key -> limiter.acquireForEmail(endpoint, key));
    }
}
