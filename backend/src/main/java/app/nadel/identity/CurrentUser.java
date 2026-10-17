package app.nadel.identity;

import app.nadel.identity.internal.MemberAuthenticationToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The caller of the current request. Inject into controllers and services instead of reading the token. */
@Component
public class CurrentUser {

    /** @throws AccessDeniedException when there is no signed-in user (never happens behind the security filter) */
    public AccessProfile profile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof MemberAuthenticationToken token) {
            return token.profile();
        }
        throw new AccessDeniedException("No signed-in user");
    }

    public Optional<UUID> memberId() {
        return Optional.ofNullable(profile().memberId());
    }

    public boolean can(Permission permission) {
        return profile().can(permission);
    }
}
