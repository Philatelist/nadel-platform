package app.nadel.notifications.internal.mail;

/** Why a mail server did not take a letter; the codes are those of the contract's {@code DeliveryFailureCode}. */
public enum MailFailure {
    /** The server refused the letter (sender, recipient or content). */
    SMTP_REJECTED,
    /** The server refused the mailbox's user name or password. */
    SMTP_AUTH_FAILED,
    /** The server could not be reached or did not answer in time. */
    CONNECTION_FAILED,
    /** The server's host or port is not one a mailbox may use ({@link SmtpHostPolicy}); nothing was connected. */
    SMTP_HOST_NOT_ALLOWED
}
