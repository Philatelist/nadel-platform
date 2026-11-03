package app.nadel.notifications.internal.mail;

/**
 * How the connection to a cooperative's mail server is protected; stored as its name in
 * {@code delivery_settings.smtp_security}. No value (null) means no encryption, for a local test server only.
 */
public enum SmtpSecurity {
    /** Plain connection that must switch to TLS before signing in (usually port 587); no switch, no letter. */
    STARTTLS,
    /** TLS from the first byte (usually port 465). */
    SSL
}
