package app.nadel.notifications.internal.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.io.IOException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.jspecify.annotations.Nullable;

/**
 * What a mail server answered to one letter. {@code serverReply} is the server's own text (its last reply line, for
 * example {@code 250 2.0.0 Ok: queued}), never a message of this application or of the mail library, so it can be
 * shown as received; it is absent when the server said nothing (it could not be reached).
 *
 * @param accepted    whether the server took the letter
 * @param serverReply the server's last reply, one line of at most {@value #MAX_REPLY} characters; null for none
 * @param failure     why the letter was not taken; null when accepted
 */
public record MailOutcome(boolean accepted, @Nullable String serverReply, @Nullable MailFailure failure) {

    static final int MAX_REPLY = 500;

    static MailOutcome accepted(@Nullable String serverReply) {
        return new MailOutcome(true, line(serverReply), null);
    }

    /**
     * The outcome of a letter the mail layer refused with {@code error}: the server refused the sign-in
     * ({@link MailFailure#SMTP_AUTH_FAILED}, with the server's reply), could not be reached
     * ({@link MailFailure#CONNECTION_FAILED}, no reply), or refused the letter ({@link MailFailure#SMTP_REJECTED}, with
     * the server's reply when it sent one).
     */
    static MailOutcome failed(Exception error) {
        if (error instanceof AuthenticationFailedException auth) {
            return new MailOutcome(false, line(auth.getMessage()), MailFailure.SMTP_AUTH_FAILED);
        }
        if (unreachable(error)) {
            return new MailOutcome(false, null, MailFailure.CONNECTION_FAILED);
        }
        return new MailOutcome(false, serverReplyOf(error), MailFailure.SMTP_REJECTED);
    }

    /** The server's reply carried by an SMTP refusal, looking through the chained exceptions; null for none. */
    private static @Nullable String serverReplyOf(Exception error) {
        for (Exception e = error; e != null; e = e instanceof MessagingException m ? m.getNextException() : null) {
            if (e instanceof SMTPSendFailedException || e instanceof SMTPAddressFailedException
                    || e instanceof SMTPSenderFailedException) {
                return line(e.getMessage());
            }
        }
        return null;
    }

    /** No connection: refused, timed out, unknown host or broken TLS, anything the server never answered. */
    private static boolean unreachable(Exception error) {
        if (error instanceof MailConnectException) {
            return true;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** One line, trimmed, of at most {@value #MAX_REPLY} characters; null for none. */
    static @Nullable String line(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        String text = reply.replaceAll("[\\r\\n]+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= MAX_REPLY ? text : text.substring(0, MAX_REPLY);
    }
}
