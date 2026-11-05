package app.nadel.members;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * SPI: sends a member the invitation into the system with its one-time set-password link (feature 020 §2.1).
 * Implemented by {@code notifications} (a letter from the cooperative's own mailbox; a later slice adds the bot); this
 * module reads it through an {@code ObjectProvider} and, without an implementation, no cooperative is ready to invite
 * ({@link #emailReady} is false).
 * <p>
 * Synchronous on purpose: the link is a secret, so it never enters the outbox, the audit log or a log line, and the
 * board sees at once whether the letter went out. Called outside any transaction (it waits for the mail server); the
 * implementation binds the cooperative itself.
 */
public interface InvitationDelivery {

    /** Whether the cooperative can send an invitation letter now (its mailbox is set up). */
    boolean emailReady(UUID tenantId);

    /**
     * Sends the invitation to the member: the letter to {@code email} with {@code link}, which works until
     * {@code expiresAt}. Never throws for anything the mail server or the network does; the result says what went out
     * or why nothing did. The link and the address are never logged.
     */
    InvitationDeliveryResult deliver(UUID tenantId, UUID memberId, String email, URI link, Instant expiresAt);
}
