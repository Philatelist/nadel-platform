package app.nadel.members.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The one-time tokens of the invitation links (feature 020): 32 random bytes from {@link SecureRandom}, written as
 * base64url without padding (43 characters, safe in a URL). Only the SHA-256 of the token's text is stored
 * ({@code member_invitation.token_hash}); the token itself exists only on the stack of the request that sends it and in
 * the letter. Never log a token.
 */
final class InvitationTokens {

    static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private InvitationTokens() {
    }

    /** A new token. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of a token: SHA-256 over its UTF-8 text, 32 bytes. Any text has one, a mistyped token too. */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
