package app.nadel.privacy.internal;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.OwnerKeyring;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unwrapped keys of one cooperative and the operations on them. Shared by the runtime cipher and the owner keyring,
 * so both produce and accept exactly the same bytes.
 * <p>
 * Data keys are not rotated in this feature: every cooperative has key version {@value #CURRENT_VERSION}, the version
 * in each sealed value leaves room for a later rotation.
 */
final class TenantKeys implements OwnerKeyring.TenantCipher {

    static final int CURRENT_VERSION = 1;
    static final String HMAC = "HmacSHA256";
    private static final Logger log = LoggerFactory.getLogger(TenantKeys.class);

    private final UUID tenantId;
    private final int version;
    private final SecretKey dataKey;
    private final SecretKey indexKey;

    TenantKeys(UUID tenantId, int version, SecretKey dataKey, SecretKey indexKey) {
        this.tenantId = tenantId;
        this.version = version;
        this.dataKey = dataKey;
        this.indexKey = indexKey;
    }

    UUID tenantId() {
        return tenantId;
    }

    int version() {
        return version;
    }

    @Override
    public byte[] encrypt(FieldRef field, String plaintext) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(plaintext, "plaintext");
        return Aead.seal(dataKey, version, plaintext.getBytes(StandardCharsets.UTF_8), context(field));
    }

    @Override
    public String decrypt(FieldRef field, byte[] sealed) {
        Objects.requireNonNull(field, "field");
        try {
            if (Aead.version(sealed) != version) {
                throw new GeneralSecurityException("Unknown key version");
            }
            return new String(Aead.open(dataKey, sealed, context(field)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // The place only: the row id is an internal id, never a value
            log.error("PERSONAL_DATA_UNREADABLE: {}.{} of row {} could not be decrypted", field.table(),
                    field.column(), field.rowId());
            throw new PersonalDataUnreadableException();
        }
    }

    @Override
    public byte[] blindIndex(IndexKind kind, String normalised) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(normalised, "normalised");
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(indexKey);
            mac.update(kind.name().getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            return mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }

    /** {@code tenantId|table|column|rowId}; UUIDs print in lower case. */
    private byte[] context(FieldRef field) {
        return (tenantId + "|" + field.table() + "|" + field.column() + "|" + field.rowId())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Never prints key material. */
    @Override
    public String toString() {
        return "TenantKeys[tenantId=" + tenantId + ", version=" + version + "]";
    }
}
