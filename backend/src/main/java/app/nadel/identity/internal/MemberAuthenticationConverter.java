package app.nadel.identity.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.AccessProfileSource;
import app.nadel.identity.Roles;
import app.nadel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated JWT into an authentication whose authorities are the {@code PERM_*} permissions of the member
 * behind the token (ADR 0009). Keycloak says who the person is and which cooperative they belong to; what they may do
 * is read from the database on every request.
 * <ul>
 *   <li>Realm role {@code PLATFORM_OPERATOR}: the operator token, with no database access (ADR 0008).</li>
 *   <li>No {@code tenant_id} claim, or no active member behind the token: {@link MemberNotLinkedException}.</li>
 * </ul>
 */
class MemberAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(MemberAuthenticationConverter.class);

    private final AccessProfileSource source;

    MemberAuthenticationConverter(AccessProfileSource source) {
        this.source = source;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (hasRealmRole(jwt, Roles.PLATFORM_OPERATOR)) {
            String name = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), jwt.getSubject());
            return new MemberAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + Roles.PLATFORM_OPERATOR)),
                    AccessProfile.operator(jwt.getSubject(), name));
        }

        UUID tenantId = tenantOf(jwt);
        if (tenantId == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            log.info("Token of subject {} has no valid tenant_id claim or no subject", jwt.getSubject());
            throw new MemberNotLinkedException("The token has no cooperative");
        }

        AccessProfile profile;
        try {
            profile = TenantContext.runAs(tenantId, () -> source.resolve(tenantId, jwt.getSubject(),
                    jwt.getClaimAsString("email"), Boolean.TRUE.equals(jwt.getClaim("email_verified"))))
                    .orElseThrow(() -> {
                        // Subject and tenant only: no e-mail or name in logs.
                        log.info("Subject {} is not linked to a member of tenant {}", jwt.getSubject(), tenantId);
                        return new MemberNotLinkedException("The user is not linked to a member");
                    });
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Database or other failure: not the caller's fault, must not look like a bad token.
            throw new AuthenticationServiceException("Could not resolve the member", e);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        profile.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.authority())));
        return new MemberAuthenticationToken(jwt, authorities, profile);
    }

    private static UUID tenantOf(Jwt jwt) {
        String claim = jwt.getClaimAsString(TenantContext.TENANT_CLAIM);
        if (claim == null || claim.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Keycloak puts realm roles in {@code realm_access.roles}. */
    private static boolean hasRealmRole(Jwt jwt, String role) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess != null
                && realmAccess.get("roles") instanceof List<?> roles
                && roles.stream().anyMatch(r -> role.equals(String.valueOf(r)));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
