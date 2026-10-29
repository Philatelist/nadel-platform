package app.nadel.notifications.internal.mail;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * One {@link JavaMailSenderImpl} per cooperative, built from its own mailbox (feature 017: every cooperative sends
 * through its own SMTP server; there is no application-wide mail server). A sender is kept while the cooperative's
 * mailbox stays the same and rebuilt as soon as any of its values (the password included) differs, so a saved change
 * takes effect with the next letter. Every connection has a connect, read and write timeout
 * ({@code nadel.delivery.smtp-timeout}), so an unreachable server cannot hold a sending thread.
 * <p>
 * Security: {@code STARTTLS} requires the switch to TLS (no silent fall back to plain text), {@code SSL} speaks TLS
 * from the start, and both check the server's certificate against its host name. Nothing here logs an address or a
 * secret.
 */
@Component
public class MailSenderFactory {

    private final Duration timeout;
    private final Map<UUID, Cached> senders = new ConcurrentHashMap<>();

    MailSenderFactory(@Value("${nadel.delivery.smtp-timeout:10s}") Duration timeout) {
        this.timeout = timeout;
    }

    /** The sender of the cooperative's mailbox: the kept one while {@code settings} are unchanged, else a new one. */
    public JavaMailSenderImpl senderFor(UUID tenantId, SmtpSettings settings) {
        return senders.compute(tenantId, (id, cached) -> cached != null && cached.settings().equals(settings)
                ? cached
                : new Cached(settings, build(settings))).sender();
    }

    private JavaMailSenderImpl build(SmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol("smtp");
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.user());
        sender.setPassword(settings.user() == null ? null : settings.password());
        sender.setDefaultEncoding("UTF-8");
        sender.setJavaMailProperties(properties(settings, timeout));
        return sender;
    }

    /** The Jakarta Mail session properties of a mailbox. */
    static Properties properties(SmtpSettings settings, Duration timeout) {
        String millis = Long.toString(timeout.toMillis());
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", millis);
        properties.setProperty("mail.smtp.timeout", millis);
        properties.setProperty("mail.smtp.writetimeout", millis);
        properties.setProperty("mail.smtp.auth", Boolean.toString(settings.user() != null));
        if (settings.security() == SmtpSecurity.STARTTLS) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        } else if (settings.security() == SmtpSecurity.SSL) {
            properties.setProperty("mail.smtp.ssl.enable", "true");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        }
        return properties;
    }

    private record Cached(SmtpSettings settings, JavaMailSenderImpl sender) {
    }
}
