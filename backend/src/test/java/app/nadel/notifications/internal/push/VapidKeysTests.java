package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.TestPushDevice;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The VAPID key pair: push is on only with all three values, a public key on the curve, a private scalar of the curve,
 * a URL subject and a matching pair; otherwise off with one WARN that names the reason and never a value, and nothing
 * is thrown, so the application always starts.
 */
@ExtendWith(OutputCaptureExtension.class)
class VapidKeysTests {

    private static final String SUBJECT = "mailto:push@nadel.test";

    private static String[] pair() {
        KeyPair pair = P256.generate();
        return new String[] {P256.base64Url(P256.encode((ECPublicKey) pair.getPublic())),
                P256.base64Url(P256.encode((ECPrivateKey) pair.getPrivate()))};
    }

    @Test
    void aValidPairTurnsPushOnWithTheKeyIdOfThePublicKey() throws Exception {
        String[] pair = pair();

        VapidKeys keys = new VapidKeys(pair[0], pair[1], SUBJECT);

        assertThat(keys.enabled()).isTrue();
        assertThat(keys.offReason()).isEmpty();
        assertThat(keys.publicKeyText()).isEqualTo(pair[0]).hasSize(87);
        assertThat(keys.subject()).isEqualTo(SUBJECT);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(pair[0]));
        assertThat(keys.serverKeyId()).hasSize(8).isEqualTo(Arrays.copyOf(digest, 8));
        assertThat(new VapidKeys(pair[0], pair[1], "https://nadel.example/contact").enabled()).isTrue();
    }

    @Test
    void aMissingValueTurnsPushOff() {
        String[] pair = pair();

        assertThat(new VapidKeys(null, pair[1], SUBJECT).offReason()).contains(VapidKeys.Reason.MISSING);
        assertThat(new VapidKeys(pair[0], "", SUBJECT).offReason()).contains(VapidKeys.Reason.MISSING);
        assertThat(new VapidKeys(pair[0], pair[1], "  ").offReason()).contains(VapidKeys.Reason.MISSING);
        assertThat(new VapidKeys(null, null, null).enabled()).isFalse();
    }

    @Test
    void aMalformedValueTurnsPushOff() {
        String[] pair = pair();
        byte[] point = Base64.getUrlDecoder().decode(pair[0]);
        point[64] ^= 1; // no longer on the curve
        byte[] compressed = Arrays.copyOf(point, 33);
        compressed[0] = 0x02;
        String n = "_____wAAAAD__________7zm-q2nF56E87nKwvxjJVE"; // the order of the curve: not a private scalar

        assertThat(new VapidKeys(P256.base64Url(point), pair[1], SUBJECT).offReason())
                .contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(P256.base64Url(compressed), pair[1], SUBJECT).offReason())
                .contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys("not base64 !", pair[1], SUBJECT).offReason()).contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(pair[0], n, SUBJECT).offReason()).contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(pair[0], "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", SUBJECT).offReason())
                .contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(pair[0], pair[1].substring(4), SUBJECT).offReason())
                .contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(pair[0], pair[1], "push@nadel.test").offReason())
                .contains(VapidKeys.Reason.MALFORMED);
        assertThat(new VapidKeys(pair[0], pair[1], "http://nadel.example").offReason())
                .contains(VapidKeys.Reason.MALFORMED);
    }

    @Test
    void aPrivateKeyOfAnotherPairTurnsPushOff() {
        String[] one = pair();
        String[] other = pair();

        assertThat(new VapidKeys(one[0], other[1], SUBJECT).offReason()).contains(VapidKeys.Reason.MISMATCH);
    }

    @Test
    void theApplicationsKeysLogOneWarningWithoutAnyValue(CapturedOutput output) {
        String[] one = pair();
        String[] other = pair();
        WebPushProperties defaults = WebPushProperties.withoutKeys();

        VapidKeys mismatched = new VapidKeys(defaults.withKeys(one[0], other[1], SUBJECT));
        VapidKeys missing = new VapidKeys(defaults);
        VapidKeys on = new VapidKeys(defaults.withKeys(one[0], one[1], SUBJECT));

        assertThat(mismatched.enabled()).isFalse();
        assertThat(missing.enabled()).isFalse();
        assertThat(on.enabled()).isTrue();
        String logged = output.getAll();
        assertThat(logged.lines().filter(line -> line.contains("Web push is off"))).hasSize(2);
        assertThat(logged).contains("are MISMATCH").contains("are MISSING")
                .doesNotContain(one[0]).doesNotContain(one[1]).doesNotContain(other[1]).doesNotContain(SUBJECT);
        assertThat(mismatched.toString()).doesNotContain(one[0]).doesNotContain(other[1]);
        assertThat(defaults.withKeys(one[0], one[1], SUBJECT).toString()).doesNotContain(one[0])
                .doesNotContain(one[1]);
    }

    @Test
    void theTestDevicesPointEncodingAgrees() {
        KeyPair pair = P256.generate();

        assertThat(TestPushDevice.point((ECPublicKey) pair.getPublic()))
                .isEqualTo(P256.encode((ECPublicKey) pair.getPublic()));
    }
}
