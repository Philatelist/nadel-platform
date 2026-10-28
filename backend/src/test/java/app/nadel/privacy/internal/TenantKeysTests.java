package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Sealing, the binding of a value to its place, and blind indexes, without a database. */
@ExtendWith(OutputCaptureExtension.class)
class TenantKeysTests {

    static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID OTHER_TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    static final String SECRET = "+380501234567";

    private final byte[] data = random();
    private final byte[] index = random();
    private final TenantKeys keys = keys(TENANT, 1, data, index);
    private final UUID row = UUID.randomUUID();
    private final FieldRef field = new FieldRef("member", "viber_phone_enc", row);

    @Test
    void roundTripAndLayout() {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertThat(keys.decrypt(field, sealed)).isEqualTo(SECRET);
        assertThat(sealed).hasSize(Aead.HEADER_BYTES + Aead.NONCE_BYTES + SECRET.length() + 16);
        assertThat(sealed[0]).isEqualTo(Aead.FORMAT);
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(SECRET);
        assertThat(keys.encrypt(field, SECRET)).as("fresh nonce").isNotEqualTo(sealed);
        assertThat(keys.decrypt(field, keys.encrypt(field, ""))).isEmpty();
        assertThat(keys.decrypt(field, keys.encrypt(field, "Київ, вул. Садова 1"))).isEqualTo("Київ, вул. Садова 1");
    }

    @Test
    void anotherRowDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> keys.decrypt(new FieldRef("member", "viber_phone_enc", UUID.randomUUID()), sealed));
    }

    @Test
    void anotherColumnDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> keys.decrypt(new FieldRef("member", "phones_enc", row), sealed));
    }

    @Test
    void anotherTableDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> keys.decrypt(new FieldRef("audit_entry", "viber_phone_enc", row), sealed));
    }

    @Test
    void anotherCooperativeDoesNotOpenEvenWithTheSameKeyBytes() {
        TenantKeys other = keys(OTHER_TENANT, 1, data, index);
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> other.decrypt(field, sealed));
    }

    @Test
    void anotherKeyDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> keys(TENANT, 1, random(), index).decrypt(field, sealed));
    }

    @Test
    void anEditedHeaderDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);
        byte[] otherFormat = sealed.clone();
        otherFormat[0] = 0x02;
        byte[] otherVersion = sealed.clone();
        otherVersion[2] = 2;
        TenantKeys versionTwo = keys(TENANT, 2, data, index);

        assertUnreadable(() -> keys.decrypt(field, otherFormat));
        assertUnreadable(() -> keys.decrypt(field, otherVersion));
        // the version is authenticated: the same key under version 2 refuses a value sealed as version 1 and the
        // header edited to 2
        assertUnreadable(() -> versionTwo.decrypt(field, otherVersion));
    }

    @Test
    void anEditedNonceOrCiphertextDoesNotOpen() {
        byte[] sealed = keys.encrypt(field, SECRET);
        byte[] nonce = sealed.clone();
        nonce[Aead.HEADER_BYTES] ^= 1;
        byte[] body = sealed.clone();
        body[sealed.length - 1] ^= 1;

        assertUnreadable(() -> keys.decrypt(field, nonce));
        assertUnreadable(() -> keys.decrypt(field, body));
    }

    @Test
    void malformedValuesDoNotOpen() {
        assertUnreadable(() -> keys.decrypt(field, null));
        assertUnreadable(() -> keys.decrypt(field, new byte[0]));
        assertUnreadable(() -> keys.decrypt(field, new byte[Aead.MIN_BYTES - 1]));
    }

    @Test
    void theLogNamesThePlaceButNeverTheValue(CapturedOutput output) {
        byte[] sealed = keys.encrypt(field, SECRET);

        assertUnreadable(() -> keys.decrypt(new FieldRef("member", "notes_enc", row), sealed));

        assertThat(output.getAll()).contains("PERSONAL_DATA_UNREADABLE: member.notes_enc of row " + row)
                .doesNotContain(SECRET);
    }

    @Test
    void blindIndexesSeparateKindsValuesAndCooperatives() {
        byte[] email = keys.blindIndex(IndexKind.EMAIL, "4567");
        byte[] phone = keys.blindIndex(IndexKind.PHONE, "4567");
        byte[] last4 = keys.blindIndex(IndexKind.PHONE_LAST4, "4567");

        assertThat(email).hasSize(32).isEqualTo(keys.blindIndex(IndexKind.EMAIL, "4567"));
        assertThat(email).isNotEqualTo(phone).isNotEqualTo(last4);
        assertThat(phone).isNotEqualTo(last4);
        assertThat(last4).isNotEqualTo(keys.blindIndex(IndexKind.PHONE_LAST4, "4568"));
        assertThat(last4).isNotEqualTo(keys(TENANT, 1, data, random()).blindIndex(IndexKind.PHONE_LAST4, "4567"));
        // "kind ‖ 0x00 ‖ value": a value cannot imitate another kind's prefix
        assertThat(keys.blindIndex(IndexKind.PHONE, "_LAST4\u00004567")).isNotEqualTo(last4);
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThatThrownBy(() -> keys.encrypt(field, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keys.encrypt(null, SECRET)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keys.decrypt(null, new byte[40])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keys.blindIndex(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keys.blindIndex(IndexKind.EMAIL, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sealingRefusesAVersionOutsideTwoBytes() {
        SecretKeySpec key = new SecretKeySpec(data, "AES");

        assertThatThrownBy(() -> Aead.seal(key, -1, new byte[1], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aead.seal(key, 0x10000, new byte[1], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Aead.seal(key, 0xFFFF, new byte[1], new byte[0])).startsWith(Aead.FORMAT, (byte) 0xFF, (byte) 0xFF);
    }

    @Test
    void toStringHasNoKeyMaterial() {
        assertThat(keys).hasToString("TenantKeys[tenantId=" + TENANT + ", version=1]");
        assertThat(keys.tenantId()).isEqualTo(TENANT);
        assertThat(keys.version()).isEqualTo(1);
    }

    private static void assertUnreadable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(PersonalDataUnreadableException.class)
                .hasMessage("Personal data could not be read.");
    }

    static TenantKeys keys(UUID tenant, int version, byte[] data, byte[] index) {
        return new TenantKeys(tenant, version, new SecretKeySpec(data, "AES"),
                new SecretKeySpec(index, TenantKeys.HMAC));
    }

    static byte[] random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
