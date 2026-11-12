package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VAPID (RFC 8292): an ES256 JWT whose signature verifies with the public key in {@code k}, {@code aud} the origin of
 * the endpoint, {@code exp} at most 24 hours ahead, {@code sub} the contact; one token per audience, renewed before it
 * runs out.
 */
class VapidSignerTests {

    private static final String SUBJECT = "mailto:push@nadel.test";

    private final KeyPair pair = P256.generate();
    private final VapidKeys keys = new VapidKeys(P256.base64Url(P256.encode((ECPublicKey) pair.getPublic())),
            P256.base64Url(P256.encode((ECPrivateKey) pair.getPrivate())), SUBJECT);
    private final MovingClock clock = new MovingClock(Instant.parse("2026-11-12T10:00:00Z"));
    private final VapidSigner signer = new VapidSigner(keys, clock);

    @Test
    void theTokenIsAnEs256JwtForTheOriginOfTheEndpoint() throws Exception {
        String header = signer.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc:def"));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys.publicKeyText());
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode head = JsonMapper.shared().readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = JsonMapper.shared().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(head.get("alg").asString()).isEqualTo("ES256");
        assertThat(head.get("typ").asString()).isEqualTo("JWT");
        assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asString()).isEqualTo(SUBJECT);
        long exp = claims.get("exp").asLong();
        assertThat(exp).isGreaterThan(clock.instant().getEpochSecond())
                .isLessThanOrEqualTo(clock.instant().plus(Duration.ofHours(24)).getEpochSecond());
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(signature).hasSize(64);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void oneTokenPerAudienceIsReusedAndRenewedBeforeItRunsOut() {
        String first = signer.authorization(URI.create("https://fcm.googleapis.com/fcm/send/one"));
        String again = signer.authorization(URI.create("https://FCM.googleapis.com:443/fcm/send/two"));
        String other = signer.authorization(URI.create("https://updates.push.services.mozilla.com/wpush/v2/x"));

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);

        clock.advance(Duration.ofHours(VapidSigner.LIFETIME_HOURS).minusMinutes(VapidSigner.RENEW_MINUTES - 1));
        assertThat(signer.authorization(URI.create("https://fcm.googleapis.com/fcm/send/one"))).isNotEqualTo(first);
    }

    @Test
    void theOriginKeepsAPortThatIsNotTheDefault() {
        assertThat(VapidSigner.origin(URI.create("http://127.0.0.1:8092/push/a"))).isEqualTo("http://127.0.0.1:8092");
        assertThat(VapidSigner.origin(URI.create("http://localhost:80/push/a"))).isEqualTo("http://localhost");
        assertThat(VapidSigner.origin(URI.create("https://web.push.apple.com/abc"))).isEqualTo(
                "https://web.push.apple.com");
    }

    @Test
    void aKeyThatCannotSignFailsWithoutAnyDetailOfTheKey() {
        VapidSigner failing = new VapidSigner(WebPushClientTests.unsignableKeys(), clock);

        assertThatThrownBy(() -> failing.authorization(URI.create("https://fcm.googleapis.com/fcm/send/one")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The VAPID token could not be signed")
                .hasNoCause();
    }

    /** A clock the test moves. */
    static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
