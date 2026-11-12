package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.TestPushDevice;
import app.nadel.notifications.internal.push.PushOutcome.Kind;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The push request against a local push service: the headers of RFC 8030/8291/8292 and a body the device decrypts;
 * the outcome of each kind of answer; a redirect is not followed; an endpoint that is not allowed is never contacted.
 */
class WebPushClientTests {

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, String>> headers = new ConcurrentHashMap<>();
    private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
    private HttpServer server;
    private String origin;
    private WebPushClient client;
    private VapidKeys keys;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            bodies.put(path, exchange.getRequestBody().readAllBytes());
            headers.put(path, Map.of("ttl", String.valueOf(exchange.getRequestHeaders().getFirst("TTL")),
                    "encoding", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Encoding")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "urgency", String.valueOf(exchange.getRequestHeaders().getFirst("Urgency"))));
            int status = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            if (status == 503) {
                exchange.getResponseHeaders().add("Retry-After", "90");
            }
            if (status == 301) {
                exchange.getResponseHeaders().add("Location", origin + "/push/201");
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        KeyPair pair = P256.generate();
        keys = new VapidKeys(P256.base64Url(P256.encode((ECPublicKey) pair.getPublic())),
                P256.base64Url(P256.encode((ECPrivateKey) pair.getPrivate())), "mailto:push@nadel.test");
        WebPushProperties properties = WebPushProperties.withoutKeys().withExtraOrigins(List.of(origin));
        client = new WebPushClient(properties, new PushEndpointPolicy(properties), new WebPushEncryptor(),
                new VapidSigner(keys, Clock.systemUTC()), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PushOutcome send(TestPushDevice device, String endpoint) {
        return client.send(URI.create(endpoint), Base64.getUrlDecoder().decode(device.p256dh()),
                Base64.getUrlDecoder().decode(device.auth()), "{\"v\":1}".getBytes(StandardCharsets.UTF_8),
                Duration.ofHours(72), null);
    }

    @Test
    void aDeliveredPushCarriesTheHeadersAndABodyTheDeviceDecrypts() {
        TestPushDevice device = new TestPushDevice();

        PushOutcome outcome = send(device, origin + "/push/201");

        assertThat(outcome.kind()).isEqualTo(Kind.DELIVERED);
        assertThat(device.decrypt(bodies.get("/push/201"))).isEqualTo("{\"v\":1}");
        Map<String, String> sent = headers.get("/push/201");
        assertThat(sent.get("ttl")).isEqualTo("259200");
        assertThat(sent.get("encoding")).isEqualTo("aes128gcm");
        assertThat(sent.get("urgency")).isEqualTo("normal");
        assertThat(sent.get("authorization")).startsWith("vapid t=").endsWith(", k=" + keys.publicKeyText());
    }

    @Test
    void eachAnswerHasItsOutcome() {
        TestPushDevice device = new TestPushDevice();

        assertThat(send(device, origin + "/push/410").kind()).isEqualTo(Kind.GONE);
        assertThat(send(device, origin + "/push/404").kind()).isEqualTo(Kind.GONE);
        assertThat(send(device, origin + "/push/429").kind()).isEqualTo(Kind.RETRY);
        PushOutcome unavailable = send(device, origin + "/push/503");
        assertThat(unavailable.kind()).isEqualTo(Kind.RETRY);
        assertThat(unavailable.retryAfter()).isEqualTo(Duration.ofSeconds(90));
        assertThat(send(device, origin + "/push/400").kind()).isEqualTo(Kind.REJECTED);
        assertThat(send(device, origin + "/push/413").kind()).isEqualTo(Kind.REJECTED);
    }

    @Test
    void aRedirectIsNotFollowed() {
        PushOutcome outcome = send(new TestPushDevice(), origin + "/push/301");

        assertThat(outcome.kind()).isEqualTo(Kind.REJECTED);
        assertThat(paths).containsExactly("/push/301");
    }

    @Test
    void noAnswerIsTriedAgain() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        WebPushProperties properties = WebPushProperties.withoutKeys()
                .withExtraOrigins(List.of("http://127.0.0.1:" + closed));
        WebPushClient toClosed = new WebPushClient(properties, new PushEndpointPolicy(properties),
                new WebPushEncryptor(), new VapidSigner(keys, Clock.systemUTC()), Clock.systemUTC());
        TestPushDevice device = new TestPushDevice();

        PushOutcome outcome = toClosed.send(URI.create("http://127.0.0.1:" + closed + "/push/1"),
                Base64.getUrlDecoder().decode(device.p256dh()), Base64.getUrlDecoder().decode(device.auth()),
                new byte[] {1}, Duration.ofMinutes(1), null);

        assertThat(outcome.kind()).isEqualTo(Kind.RETRY);
        assertThat(outcome.status()).isZero();
    }

    @Test
    void anEndpointThatIsNotAllowedIsNeverContacted() {
        TestPushDevice device = new TestPushDevice();

        PushOutcome outcome = send(device, "http://localhost:" + server.getAddress().getPort() + "/push/201");

        assertThat(outcome.kind()).isEqualTo(Kind.REJECTED);
        assertThat(paths).isEmpty();
    }
}
