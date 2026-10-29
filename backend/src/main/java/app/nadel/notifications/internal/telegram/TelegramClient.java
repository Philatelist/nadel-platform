package app.nadel.notifications.internal.telegram;

import app.nadel.notifications.internal.telegram.TelegramException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A small Telegram Bot API client on the JDK {@link HttpClient} (no third-party Telegram library), against the base
 * URL {@code nadel.telegram.api-url}. Every call is {@code POST <api-url>/bot<token>/<method>} and answers Telegram's
 * envelope {@code {"ok", "result" | "error_code", "description", "parameters"}}; {@link #call} turns a refusal into a
 * {@link TelegramException} of the right {@link Kind}.
 * <p>
 * The token is part of the URL, so neither the URL nor the token is ever logged or put into an exception message, and
 * a token with characters outside {@code [A-Za-z0-9:_-]} is refused before it reaches a URL. Calls block (the caller
 * runs them outside any transaction, on a virtual thread for the bot): {@code getMe}, the long poll
 * {@code getUpdates}, {@code sendMessage} and {@code sendDocument} (a hand-built {@code multipart/form-data} body). A
 * thread interrupted while it waits gets {@link Kind#UNAVAILABLE} with its interrupt flag set again.
 */
@Component
public class TelegramClient {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9:_-]{1,200}");
    private static final String JSON = "application/json";

    private final HttpClient http;
    private final String apiUrl;
    private final Duration readTimeout;
    private final Duration pollTimeout;
    private final JsonMapper json;

    TelegramClient(TelegramProperties properties, JsonMapper json) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String url = properties.apiUrl().toString();
        this.apiUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.readTimeout = properties.readTimeout();
        this.pollTimeout = properties.pollTimeout();
        this.json = json;
    }

    /** Who the token belongs to; {@link Kind#UNAUTHORIZED} when Telegram does not accept the token. */
    public BotIdentity getMe(String token) throws TelegramException {
        JsonNode result = call(token, "getMe", "{}", readTimeout);
        String username = result.path("username").asString("");
        if (username.isEmpty()) {
            throw new TelegramException(Kind.UNAVAILABLE, "Telegram getMe answered without a username", null);
        }
        return new BotIdentity(result.path("id").asLong(), username);
    }

    /**
     * The updates after {@code offset} (Telegram's {@code getUpdates}, messages only), waiting up to the configured
     * poll timeout ({@code nadel.telegram.poll-timeout}) for one to arrive; an empty list when none came. Asking with
     * {@code offset} confirms every update before it, so Telegram does not send it again.
     */
    public List<TelegramUpdate> getUpdates(String token, long offset) throws TelegramException {
        long seconds = Math.max(0, pollTimeout.toSeconds());
        ObjectNode body = json.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", seconds);
        body.putArray("allowed_updates").add("message");
        JsonNode result = call(token, "getUpdates", json.writeValueAsString(body),
                pollTimeout.plus(readTimeout));
        List<TelegramUpdate> updates = new ArrayList<>();
        for (JsonNode update : result) {
            JsonNode message = update.path("message");
            JsonNode chat = message.path("chat");
            TelegramUpdate.Message parsed = message.isObject() && chat.path("id").isNumber()
                    ? new TelegramUpdate.Message(chat.path("id").asLong(), chat.path("type").asString(""),
                            message.path("text").isString() ? message.path("text").asString() : null)
                    : null;
            updates.add(new TelegramUpdate(update.path("update_id").asLong(), parsed));
        }
        return updates;
    }

    /**
     * Sends a text message to a chat, with the buttons of {@code markup} when given. {@link Kind#FORBIDDEN} when the
     * member blocked the bot.
     */
    public void sendMessage(String token, long chatId, String text, @Nullable ReplyMarkup markup)
            throws TelegramException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (markup != null) {
            body.set("reply_markup", markup(markup));
        }
        call(token, "sendMessage", json.writeValueAsString(body), readTimeout);
    }

    /**
     * Sends a PDF to a chat with a caption and the buttons of {@code markup} when given ({@code sendDocument},
     * multipart). {@link Kind#FORBIDDEN} when the member blocked the bot, {@link Kind#TOO_MANY_REQUESTS} with
     * {@code retryAfter} when Telegram asks to wait.
     */
    public void sendDocument(String token, long chatId, String fileName, byte[] pdf, String caption,
            @Nullable ReplyMarkup markup) throws TelegramException {
        String boundary = "nadel-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(pdf.length + 1024);
        field(body, boundary, "chat_id", Long.toString(chatId));
        field(body, boundary, "caption", caption);
        if (markup != null) {
            field(body, boundary, "reply_markup", json.writeValueAsString(markup(markup)));
        }
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
                + safeName + "\"\r\nContent-Type: application/pdf\r\n\r\n");
        body.writeBytes(pdf);
        write(body, "\r\n--" + boundary + "--\r\n");
        HttpRequest request = HttpRequest.newBuilder(uri(token, "sendDocument"))
                .timeout(readTimeout.multipliedBy(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        send("sendDocument", request);
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode markup(ReplyMarkup markup) {
        ObjectNode node = json.createObjectNode();
        switch (markup) {
            case ReplyMarkup.Keyboard keyboard -> {
                ArrayNode rows = node.putArray("keyboard");
                for (List<String> row : keyboard.rows()) {
                    ArrayNode buttons = rows.addArray();
                    row.forEach(label -> buttons.addObject().put("text", label));
                }
                node.put("resize_keyboard", true);
            }
            case ReplyMarkup.RemoveKeyboard remove -> node.put("remove_keyboard", true);
            case ReplyMarkup.UrlButton button -> node.putArray("inline_keyboard").addArray().addObject()
                    .put("text", button.text()).put("url", button.url());
        }
        return node;
    }

    /**
     * One JSON call: {@code body} is the method's parameters as a JSON object. Answers the {@code result} of a
     * successful call.
     */
    JsonNode call(String token, String method, String body, Duration timeout) throws TelegramException {
        HttpRequest request = HttpRequest.newBuilder(uri(token, method))
                .timeout(timeout)
                .header("Content-Type", JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(method, request);
    }

    private JsonNode send(String method, HttpRequest request) throws TelegramException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // the cause is not chained: its message may hold the request URL, and with it the token
            throw new TelegramException(Kind.UNAVAILABLE,
                    "Telegram " + method + " failed: " + e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(Kind.UNAVAILABLE, "Telegram " + method + " was interrupted", null);
        }
        JsonNode envelope = parse(response.body());
        int status = response.statusCode();
        if (status == 200 && envelope != null && envelope.path("ok").asBoolean(false)) {
            return envelope.path("result");
        }
        int errorCode = envelope == null ? status : envelope.path("error_code").asInt(status);
        String message = "Telegram " + method + " failed: HTTP " + status + ", error " + errorCode;
        throw switch (errorCode) {
            case 401, 404 -> new TelegramException(Kind.UNAUTHORIZED, message, null);
            case 403 -> new TelegramException(Kind.FORBIDDEN, message, null);
            case 429 -> new TelegramException(Kind.TOO_MANY_REQUESTS, message, retryAfter(envelope));
            default -> new TelegramException(errorCode >= 400 && errorCode < 500 ? Kind.REJECTED : Kind.UNAVAILABLE,
                    message, null);
        };
    }

    private URI uri(String token, String method) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("A bot token may hold only letters, digits, ':', '_' and '-'");
        }
        return URI.create(apiUrl + "/bot" + token + "/" + method);
    }

    private @Nullable JsonNode parse(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static @Nullable Duration retryAfter(@Nullable JsonNode envelope) {
        if (envelope == null) {
            return null;
        }
        JsonNode seconds = envelope.path("parameters").path("retry_after");
        return seconds.isNumber() ? Duration.ofSeconds(seconds.asLong()) : null;
    }

    /** Binds {@link TelegramProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TelegramProperties.class)
    static class TelegramConfiguration {
    }
}
