package app.nadel;

import app.nadel.identity.FakeSignInAccounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the Keycloak JWT decoder with {@link TestTokens}, and the Keycloak Admin API ({@code identity.SignInAccounts},
 * feature 020) with the in-memory {@link FakeSignInAccounts}, so no test reaches a Keycloak by accident. The test
 * classes of {@link KeycloakIntegrationTest} set {@code nadel.test.keycloak=true} and get the real client against
 * {@link TestKeycloak}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestTokens::decode;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "nadel.test.keycloak", havingValue = "false", matchIfMissing = true)
    FakeSignInAccounts fakeSignInAccounts() {
        return new FakeSignInAccounts();
    }
}
