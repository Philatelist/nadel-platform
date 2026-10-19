package app.nadel;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock every "today" of a business rule comes from. The cooperatives live in Ukraine, so a date is a date in
 * {@code Europe/Kyiv}, whatever the zone of the server is (a UTC server is still on yesterday for up to three hours
 * after midnight in Kyiv). Tests that need a fixed day replace this bean.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    static final ZoneId COOPERATIVE_ZONE = ZoneId.of("Europe/Kyiv");

    @Bean
    Clock clock() {
        return Clock.system(COOPERATIVE_ZONE);
    }
}
