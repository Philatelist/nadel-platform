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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-JVM fake of the Telegram Bot API for the backend tests (feature 017), so no test ever reaches Telegram. It
 * serves {@code POST /bot<token>/<method>} on a random local port with Telegram's envelope
 * ({@code {"ok": true, "result": ...}} or {@code {"ok": false, "error_code", "description"}}):
 * <ul>
 *   <li>a token starting with {@code bad} gets 401 for every method, as Telegram answers an unknown token;</li>
 *   <li>{@code getMe} answers the bot {@link #username()} (default {@code nadel_test_bot});</li>
 *   <li>{@code getUpdates} is a long poll over the updates a test pushes for a token ({@link #pushMessage}): it answers
 *       the updates from {@code offset} on at once, or waits up to its {@code timeout} for one; asking with an offset
 *       confirms (drops) the updates before it, as Telegram does;</li>
 *   <li>{@code sendMessage} records the message ({@link #sent}) and answers 403 for a chat that blocked the bot
 *       ({@link #block});</li>
 *   <li>{@code sendDocument} (multipart) records the document with its caption, file and URL buttons
 *       ({@link #documents}) and answers 403 for a chat that blocked the bot;</li>
 *   <li>a method without a handler gets 404;</li>
 *   <li>{@link #handle} adds or replaces a method's handler, {@link #failNext} makes the next call of a method fail
 *       once (403 for a blocked chat, 429 with {@code retry_after}: {@link Reply#tooManyRequests}).</li>
 * </ul>
 * Every call is recorded ({@link #calls()}) with its method, the token's last four characters and the request body (a
 * multipart body as ISO-8859-1 text, byte for byte).
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

    /**
     * A message the bot sent.
     *
     * @param buttons the texts of the reply keyboard's buttons, row by row joined; empty without a keyboard
     * @param removesKeyboard whether the message took the keyboard away
     */
    public record Sent(String tokenLast4, long chatId, String text, List<String> buttons, boolean removesKeyboard) {
    }

    /** A button under a document that opens a link. */
    public record UrlButton(String text, String url) {
    }

    /** A document the bot sent: the caption, the file and the URL buttons under it. */
    public record SentDocument(String tokenLast4, long chatId, String caption, String fileName, byte[] content,
            List<UrlButton> buttons) {
    }

    private record Update(long id, String json) {
    }

    public static final String DEFAULT_USERNAME = "nadel_test_bot";
    public static final long BOT_ID = 7_000_000_001L;

    private static final Pattern PATH = Pattern.compile("^/bot([^/]+)/([A-Za-z]+)$");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static FakeBotApi shared;

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Reply>> failures = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final Object updatesLock = new Object();
    private final Map<String, List<Update>> updates = new HashMap<>();
    private final List<Sent> sent = new ArrayList<>();
    private final List<SentDocument> documents = new ArrayList<>();
    private final Set<Long> blocked = ConcurrentHashMap.newKeySet();
    private long nextUpdateId = 1_000;
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

    /**
     * Queues a private-chat text message from {@code chatId} for the bot with this token; {@code text} null is a
     * message without text (a sticker). Answers the update's id.
     */
    public long pushMessage(String token, long chatId, String text) {
        return push(token, id -> {
            ObjectNode update = JSON.createObjectNode();
            update.put("update_id", id);
            ObjectNode message = update.putObject("message");
            message.put("message_id", id);
            message.put("date", System.currentTimeMillis() / 1000);
            message.putObject("chat").put("id", chatId).put("type", "private").put("first_name", "Test");
            message.putObject("from").put("id", chatId).put("is_bot", false).put("first_name", "Test");
            if (text != null) {
                message.put("text", text);
            }
            return JSON.writeValueAsString(update);
        });
    }

    /** Queues a text message from a group chat for the bot with this token. */
    public long pushGroupMessage(String token, long chatId, String text) {
        return push(token, id -> {
            ObjectNode update = JSON.createObjectNode();
            update.put("update_id", id);
            ObjectNode message = update.putObject("message");
            message.put("message_id", id);
            message.putObject("chat").put("id", chatId).put("type", "group").put("title", "Group");
            message.put("text", text);
            return JSON.writeValueAsString(update);
        });
    }

    private long push(String token, java.util.function.LongFunction<String> json) {
        synchronized (updatesLock) {
            long id = nextUpdateId++;
            updates.computeIfAbsent(token, t -> new ArrayList<>()).add(new Update(id, json.apply(id)));
            updatesLock.notifyAll();
            return id;
        }
    }

    /** The messages the bots sent so far, oldest first. */
    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The messages sent to one chat so far, oldest first. */
    public List<Sent> sent(long chatId) {
        return sent().stream().filter(message -> message.chatId() == chatId).toList();
    }

    /** The documents the bots sent so far, oldest first. */
    public synchronized List<SentDocument> documents() {
        return List.copyOf(documents);
    }

    /** The documents sent to one chat so far, oldest first. */
    public List<SentDocument> documents(long chatId) {
        return documents().stream().filter(document -> document.chatId() == chatId).toList();
    }

    /**
     * From now on {@code sendMessage} and {@code sendDocument} to the chat answer 403, as for a member who blocked the
     * bot.
     */
    public void block(long chatId) {
        blocked.add(chatId);
    }

    /** Back to the start: the default username, the Bot API methods, no pending failures or updates, nothing recorded. */
    public synchronized void reset() {
        username = DEFAULT_USERNAME;
        handlers.clear();
        failures.clear();
        calls.clear();
        sent.clear();
        documents.clear();
        blocked.clear();
        synchronized (updatesLock) {
            updates.clear();
            updatesLock.notifyAll();
        }
        handle("getMe", (token, body) -> Reply.ok("{\"id\":" + BOT_ID + ",\"is_bot\":true,\"first_name\":\"Nadel test\","
                + "\"username\":\"" + username + "\"}"));
        handle("getUpdates", this::getUpdates);
        handle("sendMessage", this::sendMessage);
        handle("sendDocument", this::sendDocument);
    }

    private Reply getUpdates(String token, String body) {
        JsonNode request = JSON.readTree(body);
        long offset = request.path("offset").asLong(0);
        long timeoutMillis = Math.min(30, request.path("timeout").asLong(0)) * 1000;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (updatesLock) {
            while (true) {
                List<Update> queued = updates.getOrDefault(token, new ArrayList<>());
                if (offset > 0) {
                    queued.removeIf(update -> update.id() < offset);
                }
                List<Update> due = queued.stream().filter(update -> update.id() >= offset).toList();
                long left = deadline - System.currentTimeMillis();
                if (!due.isEmpty() || left <= 0) {
                    return Reply.ok("[" + String.join(",", due.stream().map(Update::json).toList()) + "]");
                }
                try {
                    updatesLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Reply.ok("[]");
                }
            }
        }
    }

    private Reply sendMessage(String token, String body) {
        JsonNode request = JSON.readTree(body);
        long chatId = request.path("chat_id").asLong();
        if (blocked.contains(chatId)) {
            return Reply.error(403, "Forbidden: bot was blocked by the user");
        }
        List<String> buttons = new ArrayList<>();
        for (JsonNode row : request.path("reply_markup").path("keyboard")) {
            List<String> labels = new ArrayList<>();
            for (JsonNode button : row) {
                labels.add(button.path("text").asString());
            }
            buttons.add(String.join(" | ", labels));
        }
        boolean removes = request.path("reply_markup").path("remove_keyboard").asBoolean(false);
        String text = request.path("text").asString();
        synchronized (this) {
            sent.add(new Sent(token.length() < 4 ? token : token.substring(token.length() - 4), chatId, text,
                    List.copyOf(buttons), removes));
        }
        return Reply.ok("{\"message_id\":1,\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"},\"text\":"
                + JSON.writeValueAsString(text) + "}");
    }

    /** The parts of a multipart body (ISO-8859-1 text, byte for byte): name to the part's raw content. */
    private static Map<String, String> parts(String body) {
        Map<String, String> parts = new HashMap<>();
        int firstLine = body.indexOf("\r\n");
        if (!body.startsWith("--") || firstLine < 0) {
            return parts;
        }
        String delimiter = "\r\n" + body.substring(0, firstLine);
        int start = firstLine + 2;
        while (true) {
            int end = body.indexOf(delimiter, start);
            if (end < 0) {
                return parts;
            }
            String part = body.substring(start, end);
            int headersEnd = part.indexOf("\r\n\r\n");
            Matcher name = Pattern.compile("name=\"([^\"]+)\"").matcher(part.substring(0, Math.max(0, headersEnd)));
            Matcher file = Pattern.compile("filename=\"([^\"]+)\"").matcher(part.substring(0, Math.max(0, headersEnd)));
            if (headersEnd >= 0 && name.find()) {
                parts.put(name.group(1), part.substring(headersEnd + 4));
                if (file.find()) {
                    parts.put(name.group(1) + ".filename", file.group(1));
                }
            }
            start = end + delimiter.length() + 2;
        }
    }

    private static String utf8(String latin1) {
        return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private Reply sendDocument(String token, String body) {
        Map<String, String> parts = parts(body);
        long chatId = Long.parseLong(parts.getOrDefault("chat_id", "0").strip());
        if (blocked.contains(chatId)) {
            return Reply.error(403, "Forbidden: bot was blocked by the user");
        }
        if (!parts.containsKey("document")) {
            return Reply.error(400, "Bad Request: there is no document in the request");
        }
        List<UrlButton> buttons = new ArrayList<>();
        if (parts.containsKey("reply_markup")) {
            for (JsonNode row : JSON.readTree(utf8(parts.get("reply_markup"))).path("inline_keyboard")) {
                for (JsonNode button : row) {
                    buttons.add(new UrlButton(button.path("text").asString(), button.path("url").asString()));
                }
            }
        }
        synchronized (this) {
            documents.add(new SentDocument(token.length() < 4 ? token : token.substring(token.length() - 4), chatId,
                    utf8(parts.getOrDefault("caption", "")), parts.getOrDefault("document.filename", ""),
                    parts.get("document").getBytes(StandardCharsets.ISO_8859_1), List.copyOf(buttons)));
        }
        return Reply.ok("{\"message_id\":2,\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"}}");
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String body = new String(raw, type != null && type.startsWith("multipart/")
                    ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
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
