package app.nadel.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.tenancy.TenantContext;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The runtime cipher against the real database: keys created lazily under RLS, sealed values round-trip. */
class PersonalDataCipherTests extends IntegrationTest {

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    OwnerKeyring ownerKeyring;

    @Test
    void aSealedValueOpensAtItsPlaceAndHasTheStoredLayout() {
        FieldRef field = new FieldRef("member", "email_enc", UUID.randomUUID());

        byte[] sealed = TenantContext.runAs(DEMO_TENANT,
                () -> cipher.encrypt(DEMO_TENANT, field, "olena.koval@example.com"));
        String opened = TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT, field, sealed));

        assertThat(opened).isEqualTo("olena.koval@example.com");
        assertThat(sealed[0]).isEqualTo((byte) 0x01);
        assertThat(((sealed[1] & 0xFF) << 8) | (sealed[2] & 0xFF)).isEqualTo(1);
        assertThat(sealed).hasSize(3 + 12 + "olena.koval@example.com".length() + 16);
    }

    @Test
    void aValueCopiedToAnotherRowDoesNotOpen() {
        FieldRef field = new FieldRef("member", "notes_enc", UUID.randomUUID());
        FieldRef otherRow = new FieldRef("member", "notes_enc", UUID.randomUUID());
        byte[] sealed = TenantContext.runAs(DEMO_TENANT, () -> cipher.encrypt(DEMO_TENANT, field, "note"));

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT, otherRow, sealed)))
                .isInstanceOf(PersonalDataUnreadableException.class)
                .hasMessage("Personal data could not be read.");
    }

    @Test
    void blindIndexesAreStablePerKindAndCooperative() {
        byte[] email = TenantContext.runAs(DEMO_TENANT,
                () -> cipher.blindIndex(DEMO_TENANT, IndexKind.EMAIL, "a@b.c"));
        byte[] again = TenantContext.runAs(DEMO_TENANT,
                () -> cipher.blindIndex(DEMO_TENANT, IndexKind.EMAIL, "a@b.c"));
        byte[] phoneKind = TenantContext.runAs(DEMO_TENANT,
                () -> cipher.blindIndex(DEMO_TENANT, IndexKind.PHONE, "a@b.c"));
        byte[] otherCooperative = TenantContext.runAs(OTHER_TENANT,
                () -> cipher.blindIndex(OTHER_TENANT, IndexKind.EMAIL, "a@b.c"));

        assertThat(email).hasSize(32).isEqualTo(again).isNotEqualTo(phoneKind).isNotEqualTo(otherCooperative);
    }

    @Test
    void anotherCooperativeThanTheCurrentOneIsRefused() {
        FieldRef field = new FieldRef("member", "email_enc", UUID.randomUUID());

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> cipher.encrypt(OTHER_TENANT, field, "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.encrypt(DEMO_TENANT, field, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theOwnerKeyringOpensWhatTheCipherSealedAndTheOtherWayRound() throws Exception {
        FieldRef field = new FieldRef("member", "phones_enc", UUID.randomUUID());
        byte[] sealedAtRuntime = TenantContext.runAs(DEMO_TENANT,
                () -> cipher.encrypt(DEMO_TENANT, field, "[\"+380501234567\"]"));

        try (Connection owner = ownerJdbc().getDataSource().getConnection()) {
            OwnerKeyring.TenantCipher demo = ownerKeyring.open(owner).forTenant(DEMO_TENANT);
            assertThat(demo.decrypt(field, sealedAtRuntime)).isEqualTo("[\"+380501234567\"]");
            byte[] sealedByOwner = demo.encrypt(field, "owner");
            assertThat(TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT, field, sealedByOwner)))
                    .isEqualTo("owner");
            assertThat(demo.blindIndex(IndexKind.PHONE_LAST4, "4567")).isEqualTo(TenantContext.runAs(DEMO_TENANT,
                    () -> cipher.blindIndex(DEMO_TENANT, IndexKind.PHONE_LAST4, "4567")));
        }
    }
}
