package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.notifications.TestPushDevice;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * RFC 8291 message encryption: the example of RFC 8291 §5 byte for byte (fixed ephemeral key and salt), a round trip
 * through the independent decryption of {@link TestPushDevice} with random keys, the header layout, and the refusals.
 */
class WebPushEncryptorTests {

    // RFC 8291 §5 "Push Message Encryption Example"
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
            + "WAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4"
            + "bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy"
            + "27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95b"
            + "Qpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxs"
            + "j_Qulcy4a-fN";

    private static byte[] b64(String text) {
        return Base64.getUrlDecoder().decode(text);
    }

    @Test
    void theExampleOfRfc8291IsReproducedByteForByte() {
        ECPublicKey asPublic = P256.publicKey(b64(AS_PUBLIC)).orElseThrow();
        ECPrivateKey asPrivate = P256.privateKey(b64(AS_PRIVATE)).orElseThrow();
        WebPushEncryptor encryptor = new WebPushEncryptor(() -> new KeyPair(asPublic, asPrivate), () -> b64(SALT));

        byte[] body = encryptor.encrypt(b64(UA_PUBLIC), b64(AUTH), PLAINTEXT.getBytes(StandardCharsets.UTF_8));

        assertThat(P256.base64Url(body)).isEqualTo(BODY);
    }

    @Test
    void theExampleDecryptsWithTheReceiversKey() {
        ECPrivateKey uaPrivate = P256.privateKey(b64(UA_PRIVATE)).orElseThrow();

        assertThat(TestPushDevice.decrypt(b64(BODY), uaPrivate, b64(UA_PUBLIC), b64(AUTH))).isEqualTo(PLAINTEXT);
    }

    @Test
    void aMessageWithRandomKeysRoundTripsThroughTheIndependentDecryption() {
        TestPushDevice device = new TestPushDevice();
        String message = "{\"v\":1,\"code\":\"INVOICES_ISSUED\",\"params\":{\"month\":\"2026-10\"}}";
        WebPushEncryptor encryptor = new WebPushEncryptor();

        byte[] first = encryptor.encrypt(b64(device.p256dh()), b64(device.auth()),
                message.getBytes(StandardCharsets.UTF_8));
        byte[] second = encryptor.encrypt(b64(device.p256dh()), b64(device.auth()),
                message.getBytes(StandardCharsets.UTF_8));

        assertThat(device.decrypt(first)).isEqualTo(message);
        assertThat(device.decrypt(second)).isEqualTo(message);
        // a new salt and a new ephemeral key per message
        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        assertThat(Arrays.copyOfRange(first, 21, 86)).isNotEqualTo(Arrays.copyOfRange(second, 21, 86));
        // the header: salt, rs 4096, idlen 65, the ephemeral point; then one record of plaintext + 0x02 + 16-byte tag
        ByteBuffer header = ByteBuffer.wrap(first);
        header.position(16);
        assertThat(header.getInt()).isEqualTo(4096);
        assertThat(header.get()).isEqualTo((byte) 65);
        assertThat(first[21]).isEqualTo((byte) 0x04);
        assertThat(first).hasSize(16 + 4 + 1 + 65 + message.length() + 1 + 16);
    }

    @Test
    void aKeyOffTheCurveAndAnOversizedMessageAreRefused() {
        byte[] offCurve = b64(UA_PUBLIC);
        offCurve[64] ^= 1;
        WebPushEncryptor encryptor = new WebPushEncryptor();

        assertThatThrownBy(() -> encryptor.encrypt(offCurve, b64(AUTH), new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryptor.encrypt(b64(UA_PUBLIC), b64(AUTH),
                new byte[WebPushEncryptor.MAX_PLAINTEXT + 1])).isInstanceOf(IllegalArgumentException.class);
    }
}
