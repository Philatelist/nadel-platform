package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may read, change and check the delivery settings (feature 017 §2.1): only a holder of {@code SETTINGS_MANAGE}
 * (the chairman), each cooperative its own; the treasurer, a member and the platform operator get 403.
 */
class DeliverySettingsAccessTests extends DeliverySettingsTestSupport {

    private static final Map<String, Object> CHANGE = Map.of("notifyPayments", false,
            "telegram", Map.of("botToken", "123456789:AAaccessTestToken0000"));

    @Test
    void theChairmanReadsChangesAndChecks() throws Exception {
        read(chairman()).andExpect(status().isOk());
        save(chairman(), CHANGE).andExpect(status().isOk()).andExpect(jsonPath("$.notifyPayments").value(false));
        testTelegram(chairman()).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        testEmail(chairman()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));
    }

    @Test
    void theTreasurerAndAMemberAreRefused() throws Exception {
        save(chairman(), CHANGE).andExpect(status().isOk());
        for (String email : List.of("treasurer@demo.local", "member@demo.local")) {
            for (ResultActions call : List.of(read(demo(email)), save(demo(email), Map.of("notifyPayments", true)),
                    testEmail(demo(email)), testTelegram(demo(email)))) {
                call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
        // nothing changed
        read(chairman()).andExpect(jsonPath("$.notifyPayments").value(false));
    }

    @Test
    void anotherCooperativeSeesOnlyItsOwnEmptySettings() throws Exception {
        save(chairman(), CHANGE).andExpect(status().isOk());
        String other = otherChairman();

        String body = read(other).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegram.configured").value(false))
                .andExpect(jsonPath("$.telegram.tokenLast4").doesNotExist())
                .andExpect(jsonPath("$.notifyPayments").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("0000");
        testTelegram(other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));

        // its own change touches only its own row
        save(other, Map.of("emailRatePerSecond", 5)).andExpect(status().isOk());
        read(chairman()).andExpect(jsonPath("$.emailRatePerSecond").value(1))
                .andExpect(jsonPath("$.telegram.tokenLast4").value("0000"));
        assertThat(ownerJdbc().queryForObject("select email_rate_per_second from delivery_settings where tenant_id = ?",
                Integer.class, OTHER_TENANT)).isEqualTo(5);
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = operator();
        for (ResultActions call : List.of(read(operator), save(operator, CHANGE), testEmail(operator),
                testTelegram(operator))) {
            call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from delivery_settings", Integer.class)).isZero();
    }
}
