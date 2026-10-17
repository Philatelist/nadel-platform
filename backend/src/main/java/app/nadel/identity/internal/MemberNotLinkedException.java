package app.nadel.identity.internal;

import org.springframework.security.core.AuthenticationException;

/** The token is valid, but it belongs to no active member of its cooperative. Answered as 403 MEMBER_NOT_LINKED. */
class MemberNotLinkedException extends AuthenticationException {

    MemberNotLinkedException(String message) {
        super(message);
    }
}
