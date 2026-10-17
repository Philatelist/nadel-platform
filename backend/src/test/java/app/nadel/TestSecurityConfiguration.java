package app.nadel;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Replaces the Keycloak JWT decoder with {@link TestTokens}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestTokens::decode;
    }
}
