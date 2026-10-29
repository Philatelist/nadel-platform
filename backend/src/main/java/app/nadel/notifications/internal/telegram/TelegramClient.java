package app.nadel.notifications.internal.telegram;

import app.nadel.notifications.internal.telegram.TelegramException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small Telegram Bot API client on the JDK {@link HttpClient} (no third-party Telegram library), against the base
 * URL {@code nadel.telegram.api-url}. Every call is {@code POST <api-url>/bot<token>/<method>} and answers Telegram's
 * envelope {@code {"ok", "result" | "error_code", "description", "parameters"}}; {@link #call} turns a refusal into a
 * {@link TelegramException} of the right {@link Kind}.
 * <p>
 * The token is part of the URL, so neither the URL nor the token is ever logged or put into an exception message, and
 * a token with characters outside {@code [A-Za-z0-9:_-]} is refused before it reaches a URL. Calls block (the caller
 * runs them outside any transaction, on a virtual thread for the bot). {@code getMe} is here now; {@code getUpdates},
 * {@code sendMessage} and {@code sendDocument} (multipart) are added as further methods over {@link #call}.
 */
@Component
public class TelegramClient {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9:_-]{1,200}");
    private static final String JSON = "application/json";

    private final HttpClient http;
    private final String apiUrl;
    private final Duration readTimeout;
    private final JsonMapper json;

    TelegramClient(TelegramProperties properties, JsonMapper json) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String url = properties.apiUrl().toString();
        this.apiUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.readTimeout = properties.readTimeout();
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
