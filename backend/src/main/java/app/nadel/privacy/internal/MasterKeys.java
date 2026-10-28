package app.nadel.privacy.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The current master key and, during a rotation, the previous one. A key is 32 bytes given in base64; its id is the
 * first 8 bytes of its SHA-256, stored beside each wrapped key ({@code tenant_data_key.master_key_id}).
 * <p>
 * Validation is by hand: the error names the environment variable and never the value, and the decoder's exception
 * (whose message can quote the offending character) is never attached as a cause.
 */
final class MasterKeys {

    static final String CURRENT_VARIABLE = "NADEL_PD_MASTER_KEY";
    static final String PREVIOUS_VARIABLE = "NADEL_PD_MASTER_KEY_PREVIOUS";
    static final int KEY_BYTES = 32;
    static final int ID_BYTES = 8;

    private final Key current;
    private final @Nullable Key previous;

    private MasterKeys(Key current, @Nullable Key previous) {
        this.current = current;
        this.previous = previous;
    }

    /**
     * @param current  base64 of the current master key; required
     * @param previous base64 of the previous master key; blank or null when there is no rotation
     * @throws IllegalStateException naming the variable that is missing or malformed
     */
    static MasterKeys of(@Nullable String current, @Nullable String previous) {
        Key currentKey = decode(current, CURRENT_VARIABLE);
        Key previousKey = previous == null || previous.isBlank() ? null : decode(previous, PREVIOUS_VARIABLE);
        if (previousKey != null && previousKey.matches(currentKey.id())) {
            previousKey = null;
        }
        return new MasterKeys(currentKey, previousKey);
    }

    Key current() {
        return current;
    }

    Optional<Key> previous() {
        return Optional.ofNullable(previous);
    }

    /** The current or previous key with this id. */
    Optional<Key> byId(byte[] id) {
        if (current.matches(id)) {
            return Optional.of(current);
        }
        return previous != null && previous.matches(id) ? Optional.of(previous) : Optional.empty();
    }

    private static Key decode(@Nullable String value, String variable) {
        String message = variable + " is missing or is not " + KEY_BYTES + " bytes of base64";
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException malformed) {
            // No cause: its message can quote a character of the key
            throw new IllegalStateException(message);
        }
        if (raw.length != KEY_BYTES) {
            Arrays.fill(raw, (byte) 0);
            throw new IllegalStateException(message);
        }
        Key key = new Key(new SecretKeySpec(raw, "AES"), fingerprint(raw));
        Arrays.fill(raw, (byte) 0);
        return key;
    }

    private static byte[] fingerprint(byte[] raw) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(raw), ID_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** One master key: the AES key and its 8-byte id. */
    static final class Key {

        private final SecretKey secret;
        private final byte[] id;

        Key(SecretKey secret, byte[] id) {
            this.secret = secret;
            this.id = id.clone();
        }

        SecretKey secret() {
            return secret;
        }

        byte[] id() {
            return id.clone();
        }

        boolean matches(byte[] other) {
            return MessageDigest.isEqual(id, other);
        }

        /** Never prints the key; the id is a public fingerprint. */
        @Override
        public String toString() {
            return "MasterKey[id=" + new String(Base64.getEncoder().encode(id), StandardCharsets.US_ASCII) + "]";
        }
    }
}
