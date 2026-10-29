package app.nadel.notifications.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most {@value #PER_MINUTE} messages a minute from one chat to its cooperative's bot (feature 017 §2.5): a chat's
 * minute starts with its first message; message 21 of that minute gets one warning, the rest of the minute is
 * ignored. In memory on the instance that holds the bot's lease. No message text is kept.
 */
@Component
class BotInputLimiter {

    static final int PER_MINUTE = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** What to do with a message. */
    enum Verdict {
        /** Handle it. */
        ALLOW,
        /** Answer «Забагато повідомлень…» once, handle nothing. */
        WARN,
        /** Ignore it. */
        DROP
    }

    private record Key(UUID tenantId, long chatId) {

        @Override
        public String toString() {
            return "Key[" + tenantId + "]";
        }
    }

    private static final class Window {
        private final Instant start;
        private int count;
        private boolean warned;

        private Window(Instant start) {
            this.start = start;
        }
    }

    private final Map<Key, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    BotInputLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts a message of the chat and says what to do with it. */
    Verdict check(UUID tenantId, long chatId) {
        Instant now = clock.instant();
        if (windows.size() > 10_000) {
            windows.values().removeIf(window -> !window.start.plus(WINDOW).isAfter(now));
        }
        Window window = windows.compute(new Key(tenantId, chatId),
                (key, current) -> current == null || !current.start.plus(WINDOW).isAfter(now) ? new Window(now)
                        : current);
        synchronized (window) {
            window.count++;
            if (window.count <= PER_MINUTE) {
                return Verdict.ALLOW;
            }
            if (!window.warned) {
                window.warned = true;
                return Verdict.WARN;
            }
            return Verdict.DROP;
        }
    }
}
