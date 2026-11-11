package app.nadel.notifications.internal.push;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a push service answered (feature 021 §2.2, RFC 8030): {@link Kind#DELIVERED} for 2xx, {@link Kind#GONE} for 404
 * and 410 (the subscription no longer exists: the device is deleted), {@link Kind#RETRY} for 429, 5xx and no answer at
 * all (sent again later, after {@code Retry-After} when the service gave one), {@link Kind#REJECTED} for every other
 * answer (counted; a device rejected five times in a row is deleted).
 *
 * @param kind       the outcome
 * @param status     the HTTP status; 0 when there was no answer
 * @param retryAfter how long the service asked to wait, when it did (429, 503)
 */
record PushOutcome(Kind kind, int status, @Nullable Duration retryAfter) {

    /** The outcome kinds. */
    enum Kind {
        DELIVERED,
        GONE,
        RETRY,
        REJECTED
    }

    PushOutcome {
        Objects.requireNonNull(kind, "kind");
    }

    /** No answer: a connection or I/O failure, a timeout, a host that does not resolve now. */
    static PushOutcome unreachable() {
        return new PushOutcome(Kind.RETRY, 0, null);
    }

    /** Not sent: the endpoint is not an allowed push service (any more), counted like a refusal. */
    static PushOutcome notAllowed() {
        return new PushOutcome(Kind.REJECTED, 0, null);
    }

    /** The outcome of an answer with this status and {@code Retry-After} header (null without one). */
    static PushOutcome of(int status, @Nullable String retryAfterHeader, Instant now) {
        if (status >= 200 && status < 300) {
            return new PushOutcome(Kind.DELIVERED, status, null);
        }
        if (status == 404 || status == 410) {
            return new PushOutcome(Kind.GONE, status, null);
        }
        if (status == 429 || status >= 500) {
            return new PushOutcome(Kind.RETRY, status, retryAfter(retryAfterHeader, now));
        }
        return new PushOutcome(Kind.REJECTED, status, null);
    }

    /**
     * {@code Retry-After} as a pause: whole seconds or an HTTP date (RFC 9110); null when absent, unreadable or not in
     * the future.
     */
    static @Nullable Duration retryAfter(@Nullable String header, Instant now) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        if (value.matches("\\d{1,9}")) {
            long seconds = Long.parseLong(value);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return at.isAfter(now) ? Duration.between(now, at) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
