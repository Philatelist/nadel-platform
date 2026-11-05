package app.nadel.members;

import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What {@link InvitationDelivery#deliver} did: the channels the link went through, or why the letter was not sent.
 *
 * @param channels the channels that carried the link; empty when nothing was sent
 * @param failure  why the letter was not sent; null when it was
 */
public record InvitationDeliveryResult(Set<DeliveryChannel> channels, @Nullable Failure failure) {

    /** Why an invitation letter was not sent; all but {@link #NOT_CONFIGURED} are the contract's delivery codes. */
    public enum Failure {
        /** The cooperative's mailbox is not set up (any more). */
        NOT_CONFIGURED,
        /** The mail server refused the letter. */
        SMTP_REJECTED,
        /** The mail server refused the mailbox's user name or password. */
        SMTP_AUTH_FAILED,
        /** The mail server could not be reached or did not answer in time. */
        CONNECTION_FAILED,
        /** The mail server is not one the application may connect to; nothing was connected. */
        SMTP_HOST_NOT_ALLOWED
    }

    public InvitationDeliveryResult {
        channels = Set.copyOf(channels);
        if ((failure == null) == channels.isEmpty()) {
            throw new IllegalArgumentException("A result is either sent through channels or failed");
        }
    }

    /** The link went through these channels (at least one). */
    public static InvitationDeliveryResult sent(Set<DeliveryChannel> channels) {
        return new InvitationDeliveryResult(channels, null);
    }

    /** Nothing was sent. */
    public static InvitationDeliveryResult failed(Failure failure) {
        return new InvitationDeliveryResult(Set.of(), Objects.requireNonNull(failure, "failure"));
    }

    /** Whether the invitation went out. */
    public boolean isSent() {
        return failure == null;
    }
}
