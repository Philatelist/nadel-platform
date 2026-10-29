package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.notifications.ClosedPort;
import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The test letter of the delivery settings (feature 017 §2.1) against Mailpit: it reaches the signed-in chairman's
 * own e-mail, a refusal shows the server's own words, an unreachable server says so, and nothing else changes.
 */
class TestEmailTests extends DeliverySettingsTestSupport {

    private final Mailpit mailpit = Mailpit.shared();

    @BeforeEach
    void emptyTheMailbox() {
        mailpit.clear();
    }

    @Test
    void theLetterReachesTheChairmansOwnEmail() throws Exception {
        save(chairman(), Map.of("email", mailbox(mailpit.host(), mailpit.smtpPort(), Mailpit.USER, Mailpit.PASSWORD)))
                .andExpect(status().isOk());

        testEmail(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.serverReply").value(org.hamcrest.Matchers.startsWith("250")))
                .andExpect(jsonPath("$.failureCode").doesNotExist());

        assertThat(mailpit.letters()).singleElement().satisfies(letter -> {
            assertThat(letter.to()).containsExactly("chairman@demo.local");
            assertThat(letter.from()).isEqualTo("board@coop.example");
            assertThat(letter.fromName()).isEqualTo("СТ «Надія»");
            assertThat(letter.subject()).isEqualTo("Тестовий лист із поштової скриньки кооперативу");
            assertThat(mailpit.text(letter.id())).contains("налаштовано правильно");
        });
    }

    @Test
    void aWrongPasswordShowsTheServersRefusalAndChangesNothing() throws Exception {
        int audit = auditEntries(DEMO_TENANT).size() + 1;
        save(chairman(), Map.of("email", mailbox(mailpit.host(), mailpit.smtpPort(), Mailpit.USER, "wrong-password")))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(DEMO_TENANT)).hasSize(audit));
        Map<String, Object> before = settingsRow();

        String body = testEmail(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.failureCode").value("SMTP_AUTH_FAILED"))
                .andExpect(jsonPath("$.serverReply").value(org.hamcrest.Matchers.startsWith("535")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Exception").doesNotContain("at org.").doesNotContain("wrong-password");
        assertThat(mailpit.letters()).isEmpty();
        assertThat(settingsRow()).isEqualTo(before);
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(auditEntries(DEMO_TENANT)).hasSize(audit);
    }

    @Test
    void aClosedPortIsAConnectionFailureWithoutAServerReply() throws Exception {
        save(chairman(), Map.of("email", mailbox("127.0.0.1", ClosedPort.next(), Mailpit.USER, Mailpit.PASSWORD)))
                .andExpect(status().isOk());

        testEmail(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.failureCode").value("CONNECTION_FAILED"))
                .andExpect(jsonPath("$.serverReply").doesNotExist());
    }

    @Test
    void aMemberWithoutAnEmailOfTheirOwnGetsNoOwnEmail() throws Exception {
        save(chairman(), Map.of("email", mailbox(mailpit.host(), mailpit.smtpPort(), Mailpit.USER, Mailpit.PASSWORD)))
                .andExpect(status().isOk());
        // a chairman signs in by e-mail once (which links the subject), then the board removes the e-mail
        String email = "no-email-chairman-" + UUID.randomUUID() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, "CHAIRMAN");
        String subject = "sub-" + email;
        read(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isOk());
        insertContacts(DEMO_TENANT, member, null);

        testEmail(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_OWN_EMAIL"));
        assertThat(mailpit.letters()).isEmpty();
    }

    @Test
    void withoutAMailboxThereIsNothingToTest() throws Exception {
        testEmail(chairman()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));

        // a mailbox without its server is not set up either
        save(chairman(), Map.of("email", Map.of("fromAddress", "board@coop.example"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.configured").value(false));
        testEmail(chairman()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));
    }

    private Map<String, Object> settingsRow() {
        return ownerJdbc().queryForMap("select smtp_host, smtp_port, smtp_user, encode(smtp_password_enc, 'hex') as pw, "
                + "settings_version, updated_at from delivery_settings where tenant_id = ?", DEMO_TENANT);
    }
}
