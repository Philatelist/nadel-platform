package app.nadel.notifications.internal.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends one plain-text letter through a cooperative's mailbox and reports what the server answered (the test letter of
 * the settings, feature 017 §2.1). Unlike {@code JavaMailSender#send} it keeps the server's own reply, so the chairman
 * sees the server's words. Runs outside any transaction (it waits for the network); the address and the texts are
 * never logged.
 */
@Component
public class TestLetters {

    private final MailSenderFactory senders;

    TestLetters(MailSenderFactory senders) {
        this.senders = senders;
    }

    /** Sends the letter to {@code to}; never throws for anything the server or the network does. */
    public MailOutcome send(UUID tenantId, SmtpSettings smtp, String to, String subject, String text) {
        JavaMailSenderImpl sender = senders.senderFor(tenantId, smtp);
        Transport transport = null;
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.fromName() == null
                    ? new InternetAddress(smtp.fromAddress())
                    : new InternetAddress(smtp.fromAddress(), smtp.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            message.saveChanges();
            transport = sender.getSession().getTransport("smtp");
            transport.connect(smtp.host(), smtp.port(), sender.getUsername(), sender.getPassword());
            transport.sendMessage(message, message.getAllRecipients());
            return MailOutcome.accepted(lastReply(transport));
        } catch (MessagingException | UnsupportedEncodingException e) {
            return MailOutcome.failed(e);
        } finally {
            closeQuietly(transport);
        }
    }

    private static @Nullable String lastReply(Transport transport) {
        return transport instanceof SMTPTransport smtp ? smtp.getLastServerResponse() : null;
    }

    private static void closeQuietly(@Nullable Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (MessagingException e) {
            // the letter's outcome is known already; a failed QUIT changes nothing
        }
    }
}
