package app.nadel.notifications.internal.mail;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One letter to one member: the subject, the plain text, an HTML version and a PDF attached. Personal data and texts:
 * {@link #toString()} prints none of it, never log it.
 *
 * @param to             the recipient's address
 * @param subject        the subject
 * @param text           the plain-text body
 * @param html           the HTML body (the same words), or null for plain text only
 * @param attachmentName the attached file's name, or null for none
 * @param attachment     the attached PDF, or null for none
 */
public record Letter(String to, String subject, String text, @Nullable String html, @Nullable String attachmentName,
        byte @Nullable [] attachment) {

    public Letter {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "Letter[html=" + (html != null) + ", attachment=" + (attachment != null) + "]";
    }
}
