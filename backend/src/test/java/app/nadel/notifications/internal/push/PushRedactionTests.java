package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.internal.push.PushSubscriptions.Recipient;
import app.nadel.notifications.internal.push.PushSubscriptions.Target;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An endpoint is a capability (whoever has it can push to the device) and the keys open the messages: the values that
 * carry them never print them, so a log line, an exception message or a debugger view that shows one of these values
 * leaks neither. Equality follows the device: a recipient by its device, member and key pair (by content), a target by
 * its device.
 */
class PushRedactionTests {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/secret-endpoint-token";
    private static final byte[] P256DH_BYTES = P256.encode((ECPublicKey) P256.generate().getPublic());
    private static final byte[] AUTH_BYTES = "sixteen byte key".getBytes(StandardCharsets.US_ASCII);
    private static final String P256DH = P256.base64Url(P256DH_BYTES);
    private static final String AUTH = P256.base64Url(AUTH_BYTES);

    private final UUID id = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    @Test
    void aTargetPrintsOnlyItsIdAndIsEqualByIt() {
        byte[] p256dh = P256DH_BYTES.clone();
        byte[] auth = AUTH_BYTES.clone();
        Target target = new Target(id, member, ENDPOINT, p256dh, auth, "uk", new byte[] {1, 2});

        assertThat(target.toString()).isEqualTo("Target[id=" + id + "]").doesNotContain("secret-endpoint-token")
                .doesNotContain(P256DH).doesNotContain(AUTH).doesNotContain(member.toString());
        Target sameDevice = new Target(id, UUID.randomUUID(), ENDPOINT + "/other", new byte[65], new byte[16], "en",
                new byte[] {3});
        assertThat(target).isEqualTo(sameDevice).hasSameHashCodeAs(sameDevice)
                .isNotEqualTo(new Target(UUID.randomUUID(), member, ENDPOINT, p256dh, auth, "uk", new byte[] {1, 2}))
                .isNotEqualTo(id);
    }

    @Test
    void aRecipientPrintsNoKeyIdAndIsEqualByDeviceMemberAndKeyPair() {
        Recipient recipient = new Recipient(id, member, new byte[] {7, 7, 7});

        assertThat(recipient.toString()).isEqualTo("Recipient[id=" + id + ", memberId=" + member + "]");
        assertThat(recipient).isEqualTo(new Recipient(id, member, new byte[] {7, 7, 7}))
                .hasSameHashCodeAs(new Recipient(id, member, new byte[] {7, 7, 7}))
                .isNotEqualTo(new Recipient(id, member, new byte[] {7, 7, 8}))
                .isNotEqualTo(new Recipient(id, UUID.randomUUID(), new byte[] {7, 7, 7}))
                .isNotEqualTo(new Recipient(UUID.randomUUID(), member, new byte[] {7, 7, 7}))
                .isNotEqualTo(id);
    }

    @Test
    void aRegistrationPrintsOnlyItsLanguage() {
        PushDevices.Registration registration = new PushDevices.Registration(ENDPOINT, P256DH, AUTH, "Phone", "uk");

        assertThat(registration.toString()).isEqualTo("Registration[language=uk]")
                .doesNotContain("secret-endpoint-token").doesNotContain(P256DH).doesNotContain(AUTH);
    }
}
