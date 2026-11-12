package app.nadel.notifications;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * An in-JVM fake of a Web Push service for the backend tests (feature 021), like {@link FakeBotApi}: no test ever
 * reaches a real push service or the dev fake of {@code infra/fake-push}. It serves {@code POST /push/<id>} on a random
 * loopback port, keeps the headers and the raw encrypted body of each push ({@link #received}) and answers 201; it
 * never decrypts (the test does, with the keys of its {@link TestPushDevice}). {@link #gone} makes an id answer 410,
 * {@link #fail} makes the next pushes of an id answer a status (with an optional {@code Retry-After}) a number of
 * times. Every Spring test context takes its origin as {@code nadel.webpush.extra-allowed-origins}
 * ({@code IntegrationTest}); a test that changes it calls {@link #reset()} afterwards.
 */
public final class FakePushService {

    /** One push that arrived. */
    public record Received(String id, Map<String, String> headers, byte[] body) {
    }

    private record Failure(int status, String retryAfter) {
    }

    private static final List<String> KEPT = List.of("Authorization", "Content-encoding", "Ttl", "Urgency", "Topic",
            "Content-type");
    private static FakePushService shared;

    private final HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final Set<String> gone = ConcurrentHashMap.newKeySet();
    private final Map<String, Deque<Failure>> failures = new ConcurrentHashMap<>();

    private FakePushService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::serve);
        server.start();
    }

    /** The service of this test JVM, started on first use. */
    public static synchronized FakePushService shared() {
        if (shared == null) {
            shared = new FakePushService();
        }
        return shared;
    }

    /** {@code http://127.0.0.1:<port>}: the origin to allow. */
    public String origin() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** The endpoint of a device with this id. */
    public String endpoint(String id) {
        return origin() + "/push/" + id;
    }

    /** Pushes to the id answer 410 Gone from now on. */
    public void gone(String id) {
        gone.add(id);
    }

    /** The next {@code times} pushes to the id answer {@code status}, with {@code Retry-After} when not null. */
    public void fail(String id, int status, int times, String retryAfter) {
        Deque<Failure> queue = failures.computeIfAbsent(id, key -> new ArrayDeque<>());
        synchronized (queue) {
            for (int i = 0; i < times; i++) {
                queue.add(new Failure(status, retryAfter));
            }
        }
    }

    /** The pushes that arrived for the id, oldest first. */
    public List<Received> received(String id) {
        synchronized (received) {
            return received.stream().filter(push -> push.id().equals(id)).toList();
        }
    }

    /** Every push that arrived, oldest first. */
    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** Forgets everything. */
    public void reset() {
        synchronized (received) {
            received.clear();
        }
        gone.clear();
        failures.clear();
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange; InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (!"POST".equals(exchange.getRequestMethod()) || !path.startsWith("/push/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String id = path.substring("/push/".length());
            if (gone.contains(id)) {
                exchange.sendResponseHeaders(410, -1);
                return;
            }
            Deque<Failure> queue = failures.get(id);
            Failure failure = null;
            if (queue != null) {
                synchronized (queue) {
                    failure = queue.poll();
                }
            }
            if (failure != null) {
                if (failure.retryAfter() != null) {
                    exchange.getResponseHeaders().add("Retry-After", failure.retryAfter());
                }
                exchange.sendResponseHeaders(failure.status(), -1);
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : KEPT) {
                String value = exchange.getRequestHeaders().getFirst(name);
                if (value != null) {
                    headers.put(name.toLowerCase(Locale.ROOT), value);
                }
            }
            synchronized (received) {
                received.add(new Received(id, headers, body));
            }
            exchange.getResponseHeaders().add("Location", "/push/" + id + "/messages/1");
            exchange.sendResponseHeaders(201, -1);
        }
    }
}
