package app.nadel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Test access tokens. A token is a random string registered together with the claims it stands for;
 * {@link TestSecurityConfiguration} decodes it back, so the real authentication converter runs in tests.
 * <pre>
 * mockMvc.perform(get("/api/v1/me").header("Authorization", TestTokens.bearer(tenant, "sub-1", "a@demo.local")));
 * </pre>
 */
public final class TestTokens {

    private static final Map<String, Jwt> ISSUED = new ConcurrentHashMap<>();

    private TestTokens() {
    }

    /** Authorization header value for a verified e-mail and no realm roles. */
    public static String bearer(UUID tenant, String subject, String email) {
        return token().tenant(tenant).subject(subject).email(email).bearer();
    }

    public static Builder token() {
        return new Builder();
    }

    static Jwt decode(String value) {
        Jwt jwt = ISSUED.get(value);
        if (jwt == null) {
            throw new BadJwtException("Unknown test token");
        }
        return jwt;
    }

    public static final class Builder {

        private UUID tenant;
        private String subject = UUID.randomUUID().toString();
        private String email;
        private boolean emailVerified = true;
        private String name;
        private List<String> realmRoles = List.of();

        public Builder tenant(UUID tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder emailVerified(boolean emailVerified) {
            this.emailVerified = emailVerified;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder realmRoles(String... realmRoles) {
            this.realmRoles = List.of(realmRoles);
            return this;
        }

        /** The {@code Authorization} header value: {@code Bearer <token>}. */
        public String bearer() {
            String value = UUID.randomUUID().toString();
            Jwt.Builder jwt = Jwt.withTokenValue(value)
                    .header("alg", "none")
                    .subject(subject)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("email_verified", emailVerified);
            if (tenant != null) {
                jwt.claim("tenant_id", tenant.toString());
            }
            if (email != null) {
                jwt.claim("email", email);
            }
            if (name != null) {
                jwt.claim("name", name);
            }
            if (!realmRoles.isEmpty()) {
                jwt.claim("realm_access", Map.of("roles", realmRoles));
            }
            ISSUED.put(value, jwt.build());
            return "Bearer " + value;
        }
    }
}
