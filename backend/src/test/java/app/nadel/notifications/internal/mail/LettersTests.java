package app.nadel.notifications.internal.mail;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Sending one letter through a cooperative's mailbox (feature 017 §2.3) against Mailpit: a sender without a name goes
 * out with its address alone, and a letter that cannot even be written (a broken sender address) is refused without
 * reaching the server and without an exception.
 */
class LettersTests {

    private final Mailpit mailpit = Mailpit.shared();
    private final Letters letters = new Letters(new MailSenderFactory(Duration.ofSeconds(10)));

    @Test
    void aSenderWithoutANameGoesOutWithItsAddressAlone() {
        String to = "letters-" + UUID.randomUUID() + "@delivery.test";

        MailOutcome outcome = letters.send(UUID.randomUUID(), mailbox("board@coop.example"),
                new Letter(to, "Рахунок", "Текст листа", null, null, null));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.failure()).isNull();
        assertThat(mailpit.lettersTo(to)).singleElement().satisfies(letter -> {
            assertThat(letter.from()).isEqualTo("board@coop.example");
            assertThat(letter.fromName()).isEmpty();
            assertThat(letter.subject()).isEqualTo("Рахунок");
        });
    }

    @Test
    void aLetterThatCannotBeWrittenIsRefusedWithoutReachingTheServer() {
        String to = "letters-" + UUID.randomUUID() + "@delivery.test";

        MailOutcome outcome = letters.send(UUID.randomUUID(), mailbox("not an address@@"),
                new Letter(to, "Рахунок", "Текст листа", null, null, null));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure()).isEqualTo(MailFailure.SMTP_REJECTED);
        assertThat(mailpit.lettersTo(to)).isEmpty();
    }

    private SmtpSettings mailbox(String fromAddress) {
        return new SmtpSettings(mailpit.host(), mailpit.smtpPort(), null, Mailpit.USER, Mailpit.PASSWORD, fromAddress,
                null);
    }
}
