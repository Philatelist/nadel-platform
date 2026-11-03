package app.nadel.notifications.internal.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The connection to a cooperative's mailbox (feature 017 §2.3): STARTTLS must switch to TLS before signing in and SSL
 * speaks TLS from the start, both checking the server's certificate against its host name; a sign-in is asked only
 * with a user; every network wait is bounded; the sender is kept while the settings stay the same; and the settings
 * never print the password.
 */
class MailSenderFactoryTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void startTlsMustSwitchToTlsAndCheckTheServersName() {
        Properties properties = MailSenderFactory.properties(settings(SmtpSecurity.STARTTLS, "board"), TIMEOUT);

        assertThat(properties).containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.auth", "true")
                .doesNotContainKey("mail.smtp.ssl.enable");
        assertThat(properties).containsEntry("mail.smtp.connectiontimeout", "10000")
                .containsEntry("mail.smtp.timeout", "10000").containsEntry("mail.smtp.writetimeout", "10000");
    }

    @Test
    void sslSpeaksTlsFromTheStartAndChecksTheServersName() {
        Properties properties = MailSenderFactory.properties(settings(SmtpSecurity.SSL, "board"), TIMEOUT);

        assertThat(properties).containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }

    @Test
    void aLocalServerWithoutSecurityOrUserGetsNoTlsAndNoSignIn() {
        Properties properties = MailSenderFactory.properties(settings(null, null), TIMEOUT);

        assertThat(properties).containsEntry("mail.smtp.auth", "false")
                .doesNotContainKeys("mail.smtp.starttls.enable", "mail.smtp.ssl.enable",
                        "mail.smtp.ssl.checkserveridentity");
    }

    @Test
    void theSenderIsKeptWhileTheSettingsStayTheSame() {
        MailSenderFactory factory = new MailSenderFactory(TIMEOUT);
        UUID tenant = UUID.randomUUID();

        JavaMailSenderImpl first = factory.senderFor(tenant, settings(SmtpSecurity.SSL, "board"));
        JavaMailSenderImpl same = factory.senderFor(tenant, settings(SmtpSecurity.SSL, "board"));
        JavaMailSenderImpl changed = factory.senderFor(tenant, settings(SmtpSecurity.STARTTLS, "board"));
        JavaMailSenderImpl withoutUser = factory.senderFor(UUID.randomUUID(), settings(null, null));

        assertThat(same).isSameAs(first);
        assertThat(changed).isNotSameAs(first);
        assertThat(changed.getJavaMailProperties()).containsEntry("mail.smtp.starttls.required", "true");
        assertThat(first.getHost()).isEqualTo("smtp.coop.example");
        assertThat(first.getPort()).isEqualTo(465);
        assertThat(first.getPassword()).isEqualTo("s3cret-pass");
        // no user, no password sent
        assertThat(withoutUser.getUsername()).isNull();
        assertThat(withoutUser.getPassword()).isNull();
    }

    @Test
    void theSettingsNeverPrintThePasswordOrTheUser() {
        String printed = settings(SmtpSecurity.SSL, "board@coop.example").toString();

        assertThat(printed).doesNotContain("s3cret-pass").doesNotContain("board@coop.example")
                .contains("smtp.coop.example").contains("password=<set>").contains("user=<set>");
        assertThat(settings(null, null).toString()).contains("user=<none>");
    }

    private static SmtpSettings settings(SmtpSecurity security, String user) {
        return new SmtpSettings("smtp.coop.example", 465, security, user, "s3cret-pass", "board@coop.example",
                "СТ «Тест»");
    }
}
