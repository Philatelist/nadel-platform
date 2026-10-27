package app.nadel.privacy.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wraps the data key and the index key of a cooperative with a master key: the {@link Aead} layout with key version 0
 * and the associated data {@code tenant_data_key|<tenantId>|<version>|data} (or {@code |index}), so a wrapped key
 * copied to another cooperative, version or purpose does not unwrap.
 */
final class KeyWrapper {

    static final int MASTER_KEY_VERSION = 0;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyWrapper() {
    }

    /** New random keys for a cooperative, wrapped with the current master key. */
    static TenantKeyRow wrapNew(MasterKeys.Key master, UUID tenantId, int version) {
        byte[] data = new byte[KEY_BYTES];
        byte[] index = new byte[KEY_BYTES];
        RANDOM.nextBytes(data);
        RANDOM.nextBytes(index);
        try {
            return wrap(master, tenantId, version, data, index);
        } finally {
            Arrays.fill(data, (byte) 0);
            Arrays.fill(index, (byte) 0);
        }
    }

    /**
     * The keys of a row, unwrapped with whichever of the master keys wrapped them.
     *
     * @throws GeneralSecurityException when neither master key has the row's id, or a wrapped key does not open
     */
    static TenantKeys unwrap(MasterKeys masterKeys, TenantKeyRow row) throws GeneralSecurityException {
        MasterKeys.Key master = masterKeys.byId(row.masterKeyId())
                .orElseThrow(() -> new GeneralSecurityException("Wrapped with an unknown master key"));
        byte[] data = Aead.open(master.secret(), row.dataKeyWrapped(), context(row.tenantId(), row.version(), "data"));
        byte[] index = Aead.open(master.secret(), row.indexKeyWrapped(),
                context(row.tenantId(), row.version(), "index"));
        try {
            return new TenantKeys(row.tenantId(), row.version(), new SecretKeySpec(data, "AES"),
                    new SecretKeySpec(index, TenantKeys.HMAC));
        } finally {
            Arrays.fill(data, (byte) 0);
            Arrays.fill(index, (byte) 0);
        }
    }

    /** The same keys wrapped with another master key ({@code from} must be the one that wrapped them). */
    static TenantKeyRow rewrap(MasterKeys.Key from, MasterKeys.Key to, TenantKeyRow row)
            throws GeneralSecurityException {
        byte[] data = Aead.open(from.secret(), row.dataKeyWrapped(), context(row.tenantId(), row.version(), "data"));
        byte[] index = Aead.open(from.secret(), row.indexKeyWrapped(),
                context(row.tenantId(), row.version(), "index"));
        try {
            return wrap(to, row.tenantId(), row.version(), data, index);
        } finally {
            Arrays.fill(data, (byte) 0);
            Arrays.fill(index, (byte) 0);
        }
    }

    private static TenantKeyRow wrap(MasterKeys.Key master, UUID tenantId, int version, byte[] data, byte[] index) {
        return new TenantKeyRow(tenantId, version,
                Aead.seal(master.secret(), MASTER_KEY_VERSION, data, context(tenantId, version, "data")),
                Aead.seal(master.secret(), MASTER_KEY_VERSION, index, context(tenantId, version, "index")),
                master.id());
    }

    private static byte[] context(UUID tenantId, int version, String purpose) {
        return ("tenant_data_key|" + tenantId + "|" + version + "|" + purpose).getBytes(StandardCharsets.UTF_8);
    }
}
