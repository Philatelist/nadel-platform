package app.nadel;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the integration tests of feature 020 that need the real Keycloak Admin API: {@link IntegrationTest} with
 * {@code nadel.keycloak.admin.url} pointing at {@link TestKeycloak} (the dev profile's secret matches the imported realm)
 * and {@code nadel.test.keycloak=true}, which keeps {@code TestSecurityConfiguration}'s fake accounts out of the context.
 * Tagged {@code keycloak}; every other Spring test uses the fake.
 * <p>
 * The container is shared by the whole test JVM and keeps every user a test makes: a test uses fresh e-mails and never
 * relies on a demo user's password once a test changed it.
 */
@Tag("keycloak")
public abstract class KeycloakIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("nadel.keycloak.admin.url", () -> TestKeycloak.shared().url());
        registry.add("nadel.test.keycloak", () -> "true");
    }
}
