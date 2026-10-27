package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.privacy.FieldRef;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Wrapped keys are bound to their cooperative, version and purpose, and to the master key that wrapped them. */
class KeyWrapperTests {

    static final UUID TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    private final MasterKeys a = MasterKeys.of(MasterKeysTests.key(32), null);
    private final MasterKeys b = MasterKeys.of(MasterKeysTests.key(32), null);
    private final FieldRef field = new FieldRef("member", "email_enc", UUID.randomUUID());

    @Test
    void newKeysUnwrapWithTheirMasterKey() throws Exception {
        TenantKeyRow row = KeyWrapper.wrapNew(a.current(), TENANT, 1);
        TenantKeys keys = KeyWrapper.unwrap(a, row);

        assertThat(row.masterKeyId()).isEqualTo(a.current().id());
        assertThat(row.dataKeyWrapped()[0]).isEqualTo(Aead.FORMAT);
        assertThat(Aead.version(row.dataKeyWrapped())).isEqualTo(KeyWrapper.MASTER_KEY_VERSION);
        assertThat(KeyWrapper.unwrap(a, row).decrypt(field, keys.encrypt(field, "x@y.z"))).isEqualTo("x@y.z");
        assertThat(row.toString()).isEqualTo("TenantKeyRow[tenantId=" + TENANT + ", version=1]");
    }

    @Test
    void anotherMasterKeyDoesNotUnwrap() {
        TenantKeyRow row = KeyWrapper.wrapNew(a.current(), TENANT, 1);
        TenantKeyRow claimingB = new TenantKeyRow(TENANT, 1, row.dataKeyWrapped(), row.indexKeyWrapped(),
                b.current().id());

        assertThatThrownBy(() -> KeyWrapper.unwrap(b, row)).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> KeyWrapper.unwrap(b, claimingB)).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void aWrappedKeyMovedToAnotherCooperativeVersionOrPurposeDoesNotUnwrap() {
        TenantKeyRow row = KeyWrapper.wrapNew(a.current(), TENANT, 1);
        byte[] id = row.masterKeyId();

        assertThatThrownBy(() -> KeyWrapper.unwrap(a,
                new TenantKeyRow(UUID.randomUUID(), 1, row.dataKeyWrapped(), row.indexKeyWrapped(), id)))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> KeyWrapper.unwrap(a,
                new TenantKeyRow(TENANT, 2, row.dataKeyWrapped(), row.indexKeyWrapped(), id)))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> KeyWrapper.unwrap(a,
                new TenantKeyRow(TENANT, 1, row.indexKeyWrapped(), row.dataKeyWrapped(), id)))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void rewrappingKeepsTheKeysAndChangesTheMasterKey() throws Exception {
        TenantKeyRow row = KeyWrapper.wrapNew(a.current(), TENANT, 1);
        TenantKeys before = KeyWrapper.unwrap(a, row);
        byte[] sealed = before.encrypt(field, "+380671112233");
        byte[] index = before.blindIndex(app.nadel.privacy.IndexKind.PHONE, "+380671112233");

        TenantKeyRow moved = KeyWrapper.rewrap(a.current(), b.current(), row);
        TenantKeys after = KeyWrapper.unwrap(b, moved);

        assertThat(moved.masterKeyId()).isEqualTo(b.current().id());
        assertThat(after.decrypt(field, sealed)).isEqualTo("+380671112233");
        assertThat(after.blindIndex(app.nadel.privacy.IndexKind.PHONE, "+380671112233")).isEqualTo(index);
        assertThatThrownBy(() -> KeyWrapper.rewrap(b.current(), a.current(), row))
                .isInstanceOf(GeneralSecurityException.class);
    }
}
