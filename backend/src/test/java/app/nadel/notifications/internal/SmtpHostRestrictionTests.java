package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.TestPropertySource;

/**
 * The mail servers a mailbox may use outside the dev profile (feature 017 §2.1, owner review of PR #61): a chairman
 * cannot save a mailbox whose host is the server itself, its private network or a metadata address, nor one on a port
 * other than 25, 465 and 587 (400 {@code SMTP_HOST_NOT_ALLOWED}, nothing saved); a mailbox that points at such a host
 * later is never connected to, neither by the test letter (400) nor by a delivery (failed at once with
 * {@code SMTP_HOST_NOT_ALLOWED}). Only IP literals are used, so no test asks DNS.
 */
@TestPropertySource(properties = "nadel.delivery.smtp-private-hosts=false")
class SmtpHostRestrictionTests extends DeliveryTestSupport {

    private static final String PUBLIC = "203.0.113.25";

    @ParameterizedTest
    @CsvSource({"127.0.0.1, 587", "localhost, 587", "169.254.169.254, 587", "10.0.0.5, 25", "192.168.1.10, 465",
        "'[fd00:ec2::254]', 587", "203.0.113.25, 1025", "203.0.113.25, 2525"})
    void aMailboxTheServerMayNotConnectToIsRefusedAndNothingIsSaved(String host, int port) throws Exception {
        send(chairman, put("/api/v1/settings/delivery"), Map.of("email", email(host, port)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SMTP_HOST_NOT_ALLOWED"));

        send(chairman, get("/api/v1/settings/delivery"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.configured").value(false))
                .andExpect(jsonPath("$.email.host").doesNotExist());
    }

    @Test
    void aPublicMailServerOnAMailPortIsSaved() throws Exception {
        send(chairman, put("/api/v1/settings/delivery"), Map.of("email", email(PUBLIC, 587)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.host").value(PUBLIC))
                .andExpect(jsonPath("$.email.configured").value(true));
    }

    @Test
    void aSavedMailboxThatNowPointsAtALocalHostIsNeverConnectedTo() throws Exception {
        send(chairman, put("/api/v1/settings/delivery"), Map.of("email", email(PUBLIC, 587)))
                .andExpect(status().isOk());
        ownerJdbc().update("update delivery_settings set smtp_host = '127.0.0.1' where tenant_id = ?", tenant);

        send(chairman, post("/api/v1/settings/delivery/test-email"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SMTP_HOST_NOT_ALLOWED"));

        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);

        DeliveryDispatcher.Run run = dispatch("EMAIL");

        assertThat(run.failed()).isEqualTo(1);
        assertThat(deliveries()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo("SMTP_HOST_NOT_ALLOWED");
            assertThat(row.get("attempts")).isEqualTo(1);
        });
        assertThat(mailpit.lettersTo("mail@delivery.test")).isEmpty();
    }

    private static Map<String, Object> email(String host, int port) {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("host", host);
        email.put("port", port);
        email.put("security", "STARTTLS");
        email.put("user", "board@rozsylka.example");
        email.put("password", "app-password-1");
        email.put("fromAddress", "board@rozsylka.example");
        email.put("fromName", "СТ «Розсилка»");
        return email;
    }
}
