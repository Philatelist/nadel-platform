package app.nadel.notifications;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-JVM fake of the Telegram Bot API for the backend tests (feature 017), so no test ever reaches Telegram. It
 * serves {@code POST /bot<token>/<method>} on a random local port with Telegram's envelope
 * ({@code {"ok": true, "result": ...}} or {@code {"ok": false, "error_code", "description"}}):
 * <ul>
 *   <li>a token starting with {@code bad} gets 401 for every method, as Telegram answers an unknown token;</li>
 *   <li>{@code getMe} answers the bot {@link #username()} (default {@code nadel_test_bot});</li>
 *   <li>a method without a handler gets 404;</li>
 *   <li>{@link #handle} adds or replaces a method's handler ({@code getUpdates}, {@code sendMessage},
 *       {@code sendDocument} in later slices), {@link #failNext} makes the next call of a method fail once (403 for a
 *       blocked chat, 429 with {@code retry_after}).</li>
 * </ul>
 * Every call is recorded ({@link #calls()}) with its method, the token's last four characters and the request body.
 * One server per test JVM ({@link #shared()}), wired into every Spring context by {@code IntegrationTest}; a test that
 * changes it calls {@link #reset()} afterwards.
 */
public final class FakeBotApi {

    /** A handler of one method: the token and the request body in, the reply out. */
    @FunctionalInterface
    public interface Handler {
        Reply handle(String token, String body);
    }

    /** An HTTP status and a JSON body. */
    public record Reply(int status, String json) {

        /** {@code {"ok": true, "result": <resultJson>}} with status 200. */
        public static Reply ok(String resultJson) {
            return new Reply(200, "{\"ok\":true,\"result\":" + resultJson + "}");
        }

        /** Telegram's error envelope with the status as {@code error_code}. */
        public static Reply error(int status, String description) {
            return new Reply(status, "{\"ok\":false,\"error_code\":" + status + ",\"description\":\"" + description
                    + "\"}");
        }

        /** 429 with {@code parameters.retry_after} in seconds. */
        public static Reply tooManyRequests(int retryAfterSeconds) {
            return new Reply(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after "
                    + retryAfterSeconds + "\",\"parameters\":{\"retry_after\":" + retryAfterSeconds + "}}");
        }
    }

    /** One recorded call. */
    public record Call(String method, String tokenLast4, String body) {
    }

    public static final String DEFAULT_USERNAME = "nadel_test_bot";
    public static final long BOT_ID = 7_000_000_001L;

    private static final Pattern PATH = Pattern.compile("^/bot([^/]+)/([A-Za-z]+)$");
    private static FakeBotApi shared;

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Reply>> failures = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private volatile String username = DEFAULT_USERNAME;

    private FakeBotApi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::serve);
        server.start();
        reset();
    }

    /** The server of this test JVM, started on first use. */
    public static synchronized FakeBotApi shared() {
        if (shared == null) {
            shared = new FakeBotApi();
        }
        return shared;
    }

    /** The base URL to set as {@code nadel.telegram.api-url}. */
    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public String username() {
        return username;
    }

    /** The @name {@code getMe} reports from now on. */
    public void username(String username) {
        this.username = username;
    }

    /** Adds or replaces the handler of a method. */
    public void handle(String method, Handler handler) {
        handlers.put(method, handler);
    }

    /** The next call of {@code method} gets {@code reply} once (before the handler and the token rule). */
    public void failNext(String method, Reply reply) {
        failures.computeIfAbsent(method, m -> new ArrayDeque<>()).add(reply);
    }

    /** The calls so far, oldest first. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The calls of one method so far, oldest first. */
    public List<Call> calls(String method) {
        return calls().stream().filter(c -> c.method().equals(method)).toList();
    }

    /** Back to the start: the default username, only {@code getMe}, no pending failures, no recorded calls. */
    public synchronized void reset() {
        username = DEFAULT_USERNAME;
        handlers.clear();
        failures.clear();
        calls.clear();
        handle("getMe", (token, body) -> Reply.ok("{\"id\":" + BOT_ID + ",\"is_bot\":true,\"first_name\":\"Nadel test\","
                + "\"username\":\"" + username + "\"}"));
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
            Reply reply;
            if (!path.matches()) {
                reply = Reply.error(404, "Not Found");
            } else {
                String token = path.group(1);
                String method = path.group(2);
                record(new Call(method, token.length() < 4 ? token : token.substring(token.length() - 4), body));
                reply = replyTo(token, method, body);
            }
            byte[] bytes = reply.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private Reply replyTo(String token, String method, String body) {
        Deque<Reply> pending = failures.get(method);
        Reply failure = pending == null ? null : pending.poll();
        if (failure != null) {
            return failure;
        }
        if (token.startsWith("bad")) {
            return Reply.error(401, "Unauthorized");
        }
        Handler handler = handlers.get(method);
        return handler == null ? Reply.error(404, "Not Found") : handler.handle(token, body);
    }

    private synchronized void record(Call call) {
        calls.add(call);
    }
}
