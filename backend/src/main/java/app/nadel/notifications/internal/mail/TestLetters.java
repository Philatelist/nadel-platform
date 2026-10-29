package app.nadel.notifications.internal.mail;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends one plain-text letter through a cooperative's mailbox and reports what the server answered (the test letter of
 * the settings, feature 017 §2.1). Unlike {@code JavaMailSender#send} it keeps the server's own reply, so the chairman
 * sees the server's words ({@link Letters}). Runs outside any transaction (it waits for the network); the address and
 * the texts are never logged.
 */
@Component
public class TestLetters {

    private final Letters letters;

    TestLetters(Letters letters) {
        this.letters = letters;
    }

    /** Sends the letter to {@code to}; never throws for anything the server or the network does. */
    public MailOutcome send(UUID tenantId, SmtpSettings smtp, String to, String subject, String text) {
        return letters.send(tenantId, smtp, new Letter(to, subject, text, null, null, null));
    }
}
