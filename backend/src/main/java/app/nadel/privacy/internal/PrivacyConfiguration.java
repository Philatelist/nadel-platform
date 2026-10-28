package app.nadel.privacy.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The master keys. In every profile except {@code dev} the key comes from {@code NADEL_PD_MASTER_KEY} only and start-up
 * fails fast without it or with a malformed one; in {@code dev} (and not {@code prod}) a missing key falls back to the
 * local key file ({@link DevMasterKeySource}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacyProperties.class)
class PrivacyConfiguration {

    @Bean
    MasterKeys masterKeys(PrivacyProperties properties, Environment environment) {
        String current = properties.masterKey();
        if ((current == null || current.isBlank()) && environment.matchesProfiles("dev & !prod")) {
            current = new DevMasterKeySource(Path.of("").toAbsolutePath(), properties.devKeyFiles()).resolve();
        }
        return MasterKeys.of(current, properties.previousMasterKey());
    }
}
