package app.nadel.notifications.internal.telegram;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Bot API call that did not succeed. The message names the method, the HTTP status and Telegram's error code only:
 * never the request URL (it holds the bot token), a chat id or a text, and no cause is chained (its message could
 * hold the URL), so the exception can be logged as it is.
 */
public final class TelegramException extends Exception {

    /** What went wrong, as far as the caller needs to know. */
    public enum Kind {
        /** 401, or 404 for a token Telegram cannot parse: the token is not valid. */
        UNAUTHORIZED,
        /** 403: the bot may not write to the chat (the member blocked it). */
        FORBIDDEN,
        /** 429: too many requests; wait {@link #retryAfter()}. */
        TOO_MANY_REQUESTS,
        /** Any other refusal of the request (400 and other 4xx). */
        REJECTED,
        /** Telegram could not be reached, did not answer in time, failed (5xx) or answered something unreadable. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final transient @Nullable Duration retryAfter;

    TelegramException(Kind kind, String message, @Nullable Duration retryAfter) {
        super(message, null, false, false);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** How long Telegram asked to wait, for {@link Kind#TOO_MANY_REQUESTS}. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
