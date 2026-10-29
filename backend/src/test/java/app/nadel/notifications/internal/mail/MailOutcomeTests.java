package app.nadel.notifications.internal.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * How a mail layer error becomes the delivery's failure code (feature 017 §2.4): a refused sign-in is
 * {@code SMTP_AUTH_FAILED} with the server's reply, a network failure anywhere in the chain is
 * {@code CONNECTION_FAILED} without a reply, anything else is {@code SMTP_REJECTED}; the reply kept is one trimmed line
 * of at most 500 characters.
 */
class MailOutcomeTests {

    @Test
    void aRefusedSignInKeepsTheServersReplyOnOneLine() {
        MailOutcome outcome = MailOutcome.failed(new AuthenticationFailedException("535 5.7.8 Authentication\r\n"
                + "credentials invalid  "));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure()).isEqualTo(MailFailure.SMTP_AUTH_FAILED);
        assertThat(outcome.serverReply()).isEqualTo("535 5.7.8 Authentication credentials invalid");
    }

    @Test
    void aNetworkFailureDeepInTheChainIsAConnectionFailureWithoutReply() {
        MailOutcome outcome = MailOutcome.failed(new MessagingException("Could not send",
                new IllegalStateException("wrapped", new SocketTimeoutException("Read timed out"))));

        assertThat(outcome.failure()).isEqualTo(MailFailure.CONNECTION_FAILED);
        assertThat(outcome.serverReply()).isNull();
    }

    @Test
    void anyOtherErrorIsARefusalAndCarriesNoReplyWhenTheServerSentNone() {
        MailOutcome outcome = MailOutcome.failed(new MessagingException("Illegal address"));

        assertThat(outcome.failure()).isEqualTo(MailFailure.SMTP_REJECTED);
        assertThat(outcome.serverReply()).isNull();
    }

    @Test
    void theReplyIsOneTrimmedLineOfAtMostFiveHundredCharacters() {
        assertThat(MailOutcome.line(null)).isNull();
        assertThat(MailOutcome.line(" \r\n ")).isNull();
        assertThat(MailOutcome.line("250 2.0.0 Ok:\nqueued")).isEqualTo("250 2.0.0 Ok: queued");
        assertThat(MailOutcome.line("x".repeat(600))).hasSize(MailOutcome.MAX_REPLY);
        assertThat(MailOutcome.accepted("  250 Ok  ").serverReply()).isEqualTo("250 Ok");
    }
}
