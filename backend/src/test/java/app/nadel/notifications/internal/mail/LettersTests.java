package app.nadel.notifications.internal.mail;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.Mailpit;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Sending one letter through a cooperative's mailbox (feature 017 §2.3) against Mailpit: a sender without a name goes
 * out with its address alone, a letter that cannot even be written (a broken sender address) is refused without
 * reaching the server and without an exception, the connection goes to the address the host policy checked (a host name
 * that no DNS knows reaches Mailpit through the address the policy resolved it to), and a mail server the policy refuses
 * is never connected to.
 */
class LettersTests {

    private final Mailpit mailpit = Mailpit.shared();
    private final Letters letters = new Letters(new MailSenderFactory(Duration.ofSeconds(10)),
            new SmtpHostPolicy(true));

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

    @Test
    void theConnectionGoesToTheAddressThePolicyChecked() throws UnknownHostException {
        InetAddress checked = InetAddress.getByName(mailpit.host());
        String unknownToDns = "mail.pinned.invalid";
        Letters pinned = new Letters(new MailSenderFactory(Duration.ofSeconds(10)), new SmtpHostPolicy(true,
                host -> unknownToDns.equals(host) ? new InetAddress[] {checked} : new InetAddress[0]));
        String to = "letters-" + UUID.randomUUID() + "@delivery.test";

        MailOutcome outcome = pinned.send(UUID.randomUUID(), new SmtpSettings(unknownToDns, mailpit.smtpPort(), null,
                Mailpit.USER, Mailpit.PASSWORD, "board@coop.example", null),
                new Letter(to, "Рахунок", "Текст листа", null, null, null));

        assertThat(outcome.accepted()).isTrue();
        assertThat(mailpit.lettersTo(to)).hasSize(1);
    }

    @Test
    void aMailServerThePolicyRefusesIsNeverConnectedTo() {
        Letters guarded = new Letters(new MailSenderFactory(Duration.ofSeconds(10)), new SmtpHostPolicy(false));
        String to = "letters-" + UUID.randomUUID() + "@delivery.test";

        MailOutcome outcome = guarded.send(UUID.randomUUID(), mailbox("board@coop.example"),
                new Letter(to, "Рахунок", "Текст листа", null, null, null));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure()).isEqualTo(MailFailure.SMTP_HOST_NOT_ALLOWED);
        assertThat(outcome.serverReply()).isNull();
        assertThat(mailpit.lettersTo(to)).isEmpty();
    }

    @Test
    void aHostThatDoesNotResolveIsAConnectionFailure() {
        Letters unresolved = new Letters(new MailSenderFactory(Duration.ofSeconds(10)), new SmtpHostPolicy(false,
                host -> {
                    throw new UnknownHostException(host);
                }));

        MailOutcome outcome = unresolved.send(UUID.randomUUID(), new SmtpSettings("smtp.nowhere.invalid", 587, null,
                null, null, "board@coop.example", null), new Letter("a@b.test", "Рахунок", "Текст", null, null, null));

        assertThat(outcome.failure()).isEqualTo(MailFailure.CONNECTION_FAILED);
    }

    private SmtpSettings mailbox(String fromAddress) {
        return new SmtpSettings(mailpit.host(), mailpit.smtpPort(), null, Mailpit.USER, Mailpit.PASSWORD, fromAddress,
                null);
    }
}
