package app.nadel.identity.internal;

import app.nadel.identity.AccessProfile;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A validated JWT together with the {@link AccessProfile} resolved for it. It stays a
 * {@link JwtAuthenticationToken}, so {@code TenantContext.currentTenantId()} keeps reading the tenant claim.
 * Public only because {@code CurrentUser} in the module root reads it; other modules use {@code CurrentUser}.
 */
public class MemberAuthenticationToken extends JwtAuthenticationToken {

    private final AccessProfile profile;

    MemberAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, AccessProfile profile) {
        // The principal name is the Keycloak subject, never the e-mail or the name.
        super(jwt, authorities, profile.subject());
        this.profile = profile;
    }

    public AccessProfile profile() {
        return profile;
    }
}
