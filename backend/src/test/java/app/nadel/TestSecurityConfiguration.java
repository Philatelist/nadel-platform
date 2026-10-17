package app.nadel;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Replaces the Keycloak JWT decoder with {@link TestTokens} and adds probe endpoints for permission checks. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestTokens::decode;
    }

    /**
     * An endpoint guarded exactly like a real one (ADR 0009), to test the authority mapping and the 403 answer for a
     * permission that has no real endpoint yet (MEMBERS_EDIT; remove this probe when the first write endpoint of
     * members exists). Real guarded endpoints are tested through their own API, for example {@code GET /members}.
     * Picked up by component scanning of the test classpath.
     */
    @RestController
    public static class PermissionProbe {

        @GetMapping("/api/v1/_probe/members-edit")
        @PreAuthorize("hasAuthority('PERM_MEMBERS_EDIT')")
        public String membersEdit() {
            return "ok";
        }
    }
}
