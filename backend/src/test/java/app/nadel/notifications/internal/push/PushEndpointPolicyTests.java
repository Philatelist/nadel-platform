package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.internal.push.PushEndpointPolicy.Verdict;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which endpoints the server sends to: https on a known push service (a name, or a subdomain of a {@code *.} entry),
 * no user information or fragment, every resolved address public; an extra origin of the configuration may be http and
 * private. Everything else is refused, also when it only looks like a push service.
 */
class PushEndpointPolicyTests {

    private static final Map<String, String> DNS = Map.of(
            "fcm.googleapis.com", "142.250.0.95",
            "updates.push.services.mozilla.com", "34.117.0.1",
            "db5p.notify.windows.com", "20.0.0.1",
            "web.push.apple.com", "17.0.0.1",
            "evil.push.services.mozilla.com", "169.254.169.254");

    private final PushEndpointPolicy policy = new PushEndpointPolicy(
            WebPushProperties.withoutKeys().withExtraOrigins(List.of("http://localhost:8092")), host -> {
                String address = DNS.get(host);
                if ("localhost".equals(host)) {
                    return new InetAddress[] {InetAddress.getLoopbackAddress()};
                }
                if (address == null) {
                    throw new UnknownHostException();
                }
                return new InetAddress[] {InetAddress.getByName(address)};
            });

    @Test
    void theKnownPushServicesAreAllowed() {
        for (String endpoint : List.of("https://fcm.googleapis.com/fcm/send/abc:APA91b",
                "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
                "https://db5p.notify.windows.com/w/?token=BQYAAA",
                "https://web.push.apple.com/QGuQyavXutnMH")) {
            assertThat(policy.syntaxAllowed(endpoint)).as(endpoint).isPresent();
            assertThat(policy.check(URI.create(endpoint))).as(endpoint).isEqualTo(Verdict.ALLOWED);
        }
    }

    @Test
    void anythingElseIsRefusedByItsText() {
        for (String endpoint : List.of("http://fcm.googleapis.com/fcm/send/abc",
                "https://user:pass@fcm.googleapis.com/fcm/send/abc",
                "https://fcm.googleapis.com/fcm/send/abc#x",
                "https://fcm.googleapis.com.evil.example/fcm/send/abc",
                "https://evilfcm.googleapis.com/fcm/send/abc",
                "https://push.services.mozilla.com/wpush/v2/x",
                "https://example.com/push",
                "https://127.0.0.1/push",
                "ftp://fcm.googleapis.com/x",
                "fcm.googleapis.com/fcm/send/abc",
                "mailto:push@fcm.googleapis.com",
                "http://localhost:8093/push/a",
                "https://localhost:8092/push/a",
                "",
                "https://fcm.googleapis.com/fcm/send/a b")) {
            assertThat(policy.syntaxAllowed(endpoint)).as(endpoint).isEmpty();
        }
    }

    @Test
    void aKnownNameThatResolvesToAPrivateAddressIsRefusedBeforeTheSend() {
        String endpoint = "https://evil.push.services.mozilla.com/wpush/v2/x";

        assertThat(policy.syntaxAllowed(endpoint)).isPresent();
        assertThat(policy.check(URI.create(endpoint))).isEqualTo(Verdict.NOT_ALLOWED);
        assertThat(policy.check(URI.create("https://unknown.push.services.mozilla.com/x")))
                .isEqualTo(Verdict.UNRESOLVED);
        assertThat(policy.check(URI.create("https://example.com/push"))).isEqualTo(Verdict.NOT_ALLOWED);
    }

    @Test
    void anExtraOriginMayBePlainHttpOnALoopbackAddress() {
        assertThat(policy.syntaxAllowed("http://localhost:8092/push/abc")).isPresent();
        assertThat(policy.check(URI.create("http://localhost:8092/push/abc"))).isEqualTo(Verdict.ALLOWED);
        assertThat(policy.check(URI.create("http://LOCALHOST:8092/push/abc"))).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void withoutExtraOriginsTheDevServiceIsRefused() {
        PushEndpointPolicy production = new PushEndpointPolicy(WebPushProperties.withoutKeys(),
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()});

        assertThat(production.syntaxAllowed("http://localhost:8092/push/abc")).isEmpty();
        assertThat(production.check(URI.create("https://fcm.googleapis.com/fcm/send/x")))
                .isEqualTo(Verdict.NOT_ALLOWED);
    }
}
