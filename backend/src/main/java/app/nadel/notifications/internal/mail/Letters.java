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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends one letter through a cooperative's mailbox ({@link MailSenderFactory}) and reports what the server answered:
 * the server's own last reply and, for a refusal, a {@link MailFailure}. The sender is the mailbox's address with its
 * name; a letter with HTML is {@code multipart/alternative} (plain text and HTML), a PDF goes along as an attachment.
 * Runs outside any transaction (it waits for the network); addresses and texts are never logged.
 */
@Component
public class Letters {

    private final MailSenderFactory senders;

    Letters(MailSenderFactory senders) {
        this.senders = senders;
    }

    /** Sends the letter; never throws for anything the server or the network does. */
    public MailOutcome send(UUID tenantId, SmtpSettings smtp, Letter letter) {
        JavaMailSenderImpl sender = senders.senderFor(tenantId, smtp);
        Transport transport = null;
        try {
            MimeMessage message = sender.createMimeMessage();
            boolean multipart = letter.html() != null || letter.attachment() != null;
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.fromName() == null
                    ? new InternetAddress(smtp.fromAddress())
                    : new InternetAddress(smtp.fromAddress(), smtp.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(letter.to());
            helper.setSubject(letter.subject());
            if (letter.html() != null) {
                helper.setText(letter.text(), letter.html());
            } else {
                helper.setText(letter.text(), false);
            }
            if (letter.attachment() != null && letter.attachmentName() != null) {
                helper.addAttachment(letter.attachmentName(), new ByteArrayResource(letter.attachment()),
                        "application/pdf");
            }
            message.saveChanges();
            transport = sender.getSession().getTransport("smtp");
            transport.connect(smtp.host(), smtp.port(), sender.getUsername(), sender.getPassword());
            transport.sendMessage(message, message.getAllRecipients());
            return MailOutcome.accepted(lastReply(transport));
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
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
