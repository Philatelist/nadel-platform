package app.nadel.notifications.internal.mail;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A cooperative's mailbox, opened: what {@link MailSenderFactory} connects with. Holds the password in the clear, so it
 * lives only in memory and {@link #toString()} never prints it.
 *
 * @param host        the mail server
 * @param port        its port
 * @param security    how the connection is protected; null for none
 * @param user        the user name to sign in with; null for a server without sign-in
 * @param password    the password; null without one
 * @param fromAddress the sender's address
 * @param fromName    the sender's name; null for none
 */
public record SmtpSettings(String host, int port, @Nullable SmtpSecurity security, @Nullable String user,
        @Nullable String password, String fromAddress, @Nullable String fromName) {

    public SmtpSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(fromAddress, "fromAddress");
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "SmtpSettings[host=" + host + ", port=" + port + ", security=" + security + ", user="
                + (user == null ? "<none>" : "<set>") + ", password=" + (password == null ? "<none>" : "<set>") + "]";
    }
}
