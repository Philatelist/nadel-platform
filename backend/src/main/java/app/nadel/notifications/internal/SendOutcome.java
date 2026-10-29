package app.nadel.notifications.internal;

import java.time.Duration;
import java.util.Objects;

/** What one try to send a delivery came to. */
sealed interface SendOutcome {

    /** The mail server or Telegram took the message. */
    record Sent() implements SendOutcome {
    }

    /**
     * The message was not taken. A permanent failure fails the delivery at once; another is tried again until the
     * attempts are used up.
     *
     * @param code      the failure code ({@code delivery.failure_code})
     * @param permanent whether trying again cannot help
     */
    record Failed(String code, boolean permanent) implements SendOutcome {

        public Failed {
            Objects.requireNonNull(code, "code");
        }
    }

    /** Telegram asked to wait ({@code 429}) before the next message; the attempt does not count. */
    record Wait(Duration retryAfter) implements SendOutcome {
    }

    /** The failure codes. */
    String SMTP_REJECTED = "SMTP_REJECTED";
    String SMTP_AUTH_FAILED = "SMTP_AUTH_FAILED";
    String CONNECTION_FAILED = "CONNECTION_FAILED";
    String TELEGRAM_BLOCKED = "TELEGRAM_BLOCKED";
    String CHANNEL_NOT_CONFIGURED = "CHANNEL_NOT_CONFIGURED";
    String NO_CONTACT = "NO_CONTACT";

    static SendOutcome permanent(String code) {
        return new Failed(code, true);
    }

    static SendOutcome transientFailure(String code) {
        return new Failed(code, false);
    }
}
