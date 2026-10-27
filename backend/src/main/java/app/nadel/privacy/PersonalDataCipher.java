package app.nadel.privacy;

import java.util.UUID;

/**
 * Seals and opens personal values of the current cooperative and computes their blind indexes (ADR 0016).
 * <p>
 * Every method takes the cooperative explicitly and refuses one that is not {@code TenantContext.currentTenantId()},
 * so a value is never sealed or opened with the key of another cooperative. The keys are read (and created on first
 * use) through the caller's tenant-aware transaction and cached per instance for at most ten minutes.
 */
public interface PersonalDataCipher {

    /**
     * Seals {@code plaintext} for the given place: {@code [0x01][u16 key version][12-byte nonce][ciphertext ‖ tag]}.
     * The place (cooperative, table, column, row id) is the associated data, so the result cannot be opened anywhere
     * else.
     */
    byte[] encrypt(UUID tenantId, FieldRef field, String plaintext);

    /**
     * Opens a value sealed by {@link #encrypt} for the same place.
     *
     * @throws PersonalDataUnreadableException when the value is malformed, was sealed for another place or with
     *         another key
     */
    String decrypt(UUID tenantId, FieldRef field, byte[] sealed);

    /**
     * The 32-byte HMAC-SHA-256 blind index of an already normalised value: the same value of the same kind in the same
     * cooperative always gives the same bytes, and different kinds never collide.
     */
    byte[] blindIndex(UUID tenantId, IndexKind kind, String normalised);
}
