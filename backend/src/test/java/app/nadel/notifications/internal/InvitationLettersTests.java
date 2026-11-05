package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.notifications.Mailpit;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The invitation letter of feature 020 ({@code members.InvitationDelivery}, {@link InvitationLetters}) against
 * Mailpit: ready only with a mailbox, the letter «Задайте пароль» from the cooperative's mailbox with the link and its
 * expiry in the cooperative's time, the failure codes, and neither the link nor the address in the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class InvitationLettersTests extends DeliverySettingsTestSupport {

    private static final URI LINK = URI.create("http://localhost:5173/set-password?token=abcDEF123_-xyz");

    @Autowired
    InvitationLetters letters;

    private final Mailpit mailpit = Mailpit.shared();

    @BeforeEach
    void emptyTheMailbox() {
        mailpit.clear();
    }

    @Test
    void withoutAMailboxNothingIsReadyAndNothingIsSent() {
        assertThat(letters.emailReady(DEMO_TENANT)).isFalse();

        InvitationDeliveryResult result = letters.deliver(DEMO_TENANT, UUID.randomUUID(), "invitee@example.com", LINK,
                Instant.now());

        assertThat(result.failure()).isEqualTo(InvitationDeliveryResult.Failure.NOT_CONFIGURED);
        assertThat(mailpit.letters()).isEmpty();
    }

    @Test
    void theLetterCarriesTheLinkAndItsExpiry(CapturedOutput output) throws Exception {
        save(chairman(), Map.of("email", mailbox(mailpit.host(), mailpit.smtpPort(), Mailpit.USER, Mailpit.PASSWORD)))
                .andExpect(status().isOk());
        assertThat(letters.emailReady(DEMO_TENANT)).isTrue();
        assertThat(letters.emailReady(OTHER_TENANT)).as("another cooperative has no mailbox").isFalse();

        InvitationDeliveryResult result = letters.deliver(DEMO_TENANT, UUID.randomUUID(), "invitee@example.com", LINK,
                Instant.parse("2026-11-08T12:30:00Z"));

        assertThat(result.isSent()).isTrue();
        assertThat(result.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(mailpit.letters()).singleElement().satisfies(letter -> {
            assertThat(letter.to()).containsExactly("invitee@example.com");
            assertThat(letter.from()).isEqualTo("board@coop.example");
            assertThat(letter.subject()).startsWith("Задайте пароль");
            assertThat(mailpit.text(letter.id())).contains(LINK.toString())
                    .contains("invitee@example.com")
                    .contains("08.11.2026 14:30"); // Kyiv time
        });
        assertThat(output.getAll()).contains("Invitation letter of cooperative").doesNotContain(LINK.toString())
                .doesNotContain("abcDEF123").doesNotContain("invitee@example.com");
    }

    @Test
    void aMailServerThatRefusesIsAFailureCode() throws Exception {
        save(chairman(), Map.of("email", mailbox(mailpit.host(), mailpit.smtpPort(), Mailpit.USER, "wrong-password")))
                .andExpect(status().isOk());

        InvitationDeliveryResult result = letters.deliver(DEMO_TENANT, UUID.randomUUID(), "invitee@example.com", LINK,
                Instant.now());

        assertThat(result.isSent()).isFalse();
        assertThat(result.failure()).isEqualTo(InvitationDeliveryResult.Failure.SMTP_AUTH_FAILED);
        assertThat(mailpit.letters()).isEmpty();
    }
}
