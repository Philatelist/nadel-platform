package app.nadel.privacy.internal;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The runtime cipher: keys of the current cooperative from the cache, loaded through {@link TenantKeyStore}. */
@Component
class DefaultPersonalDataCipher implements PersonalDataCipher {

    private final TenantKeyCache cache;
    private final TenantKeyStore store;

    DefaultPersonalDataCipher(TenantKeyCache cache, TenantKeyStore store) {
        this.cache = cache;
        this.store = store;
    }

    @Override
    public byte[] encrypt(UUID tenantId, FieldRef field, String plaintext) {
        return keys(tenantId).encrypt(field, plaintext);
    }

    @Override
    public String decrypt(UUID tenantId, FieldRef field, byte[] sealed) {
        return keys(tenantId).decrypt(field, sealed);
    }

    @Override
    public byte[] blindIndex(UUID tenantId, IndexKind kind, String normalised) {
        return keys(tenantId).blindIndex(kind, normalised);
    }

    private TenantKeys keys(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (!tenantId.equals(TenantContext.currentTenantId().orElse(null))) {
            throw new IllegalStateException("Personal data is sealed and opened only for the current cooperative");
        }
        return cache.keys(tenantId, store::load);
    }
}
