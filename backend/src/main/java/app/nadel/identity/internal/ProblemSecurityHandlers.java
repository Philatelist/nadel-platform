package app.nadel.identity.internal;

import app.nadel.problem.ProblemCodes;
import app.nadel.problem.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Security failures as Problem Details. They happen in the filter chain, before Spring MVC, so the controller
 * advice never sees them.
 */
@Component
class ProblemSecurityHandlers {

    private final ProblemWriter writer;
    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

    ProblemSecurityHandlers(ProblemWriter writer) {
        this.writer = writer;
    }

    /**
     * 403 {@code MEMBER_NOT_LINKED} for a valid token without an active member; 500 when the member lookup itself
     * failed; otherwise 401 {@code UNAUTHENTICATED} with the standard {@code WWW-Authenticate} challenge.
     */
    AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            if (ex instanceof MemberNotLinkedException) {
                writer.write(response, HttpStatus.FORBIDDEN, ProblemCodes.MEMBER_NOT_LINKED,
                        "The signed-in user is not linked to a member of the cooperative.");
            } else if (ex instanceof AuthenticationServiceException) {
                writer.write(response, HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.INTERNAL_ERROR,
                        "The server could not process the request.");
            } else {
                unauthenticated(request, response, ex);
            }
        };
    }

    /** 403 {@code PERMISSION_DENIED}. */
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> writer.write(response, HttpStatus.FORBIDDEN,
                ProblemCodes.PERMISSION_DENIED, "You do not have permission to perform this action.");
    }

    private void unauthenticated(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException ex) throws IOException {
        bearer.commence(request, response, ex); // sets 401 and the WWW-Authenticate challenge
        writer.write(response, HttpStatus.UNAUTHORIZED, ProblemCodes.UNAUTHENTICATED,
                "A valid access token is required.");
    }
}
