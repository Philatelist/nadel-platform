package app.nadel.notifications.internal.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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
 * The mail server's host is resolved and checked once by {@link SmtpHostPolicy} before every letter, and the connection
 * goes to the checked address ({@link PinnedSocketFactory}); a host that is not allowed is
 * {@link MailFailure#SMTP_HOST_NOT_ALLOWED} and nothing is connected. Runs outside any transaction (it waits for the
 * network); addresses and texts are never logged.
 */
@Component
public class Letters {

    private final MailSenderFactory senders;
    private final SmtpHostPolicy hosts;

    Letters(MailSenderFactory senders, SmtpHostPolicy hosts) {
        this.senders = senders;
        this.hosts = hosts;
    }

    /** Sends the letter; never throws for anything the server or the network does. */
    public MailOutcome send(UUID tenantId, SmtpSettings smtp, Letter letter) {
        InetAddress address;
        try {
            address = hosts.connectTo(smtp.host(), smtp.port());
        } catch (SmtpHostPolicy.HostNotAllowedException e) {
            return MailOutcome.hostNotAllowed();
        } catch (UnknownHostException e) {
            return MailOutcome.failed(e);
        }
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
            transport = pinnedSession(sender, address).getTransport("smtp");
            transport.connect(smtp.host(), smtp.port(), sender.getUsername(), sender.getPassword());
            transport.sendMessage(message, message.getAllRecipients());
            return MailOutcome.accepted(lastReply(transport));
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
            return MailOutcome.failed(e);
        } finally {
            closeQuietly(transport);
        }
    }

    /** A session of the sender's settings whose connection goes only to {@code address}. */
    private static Session pinnedSession(JavaMailSenderImpl sender, InetAddress address) {
        Properties properties = new Properties();
        properties.putAll(sender.getJavaMailProperties());
        properties.put("mail.smtp.socketFactory", new PinnedSocketFactory(address));
        // a failing pinned socket must not fall back to the library's own socket, which would look the host up again
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        return Session.getInstance(properties);
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
