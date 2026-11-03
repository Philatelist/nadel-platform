package app.nadel.notifications.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The outbound pace of the delivery worker (feature 017 §2.4): through one cooperative's bot at most
 * {@code nadel.delivery.telegram-per-second} messages a second (20) and to one member's chat at most one a
 * {@code telegram-chat-gap} (1 s); through one cooperative's mailbox at most its {@code email_rate_per_second}
 * letters a second. Each limit spaces the sends evenly: a caller reserves the next free slot of every limit it is
 * under and waits until the latest of them.
 * <p>
 * In memory, per instance: the lane lease ({@link DeliveryLanes}) makes one instance send a lane at a time, so its
 * pace is the lane's. The clock and the pause are injected, so the tests check the pace without waiting. Keys are ids
 * only, never a chat id or an address.
 */
@Component
class RateLimiters {

    /** How a caller waits; {@link Thread#sleep} outside the tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Above this many remembered slots the stale ones are dropped. */
    private static final int MAX_KEYS = 10_000;

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration chatGap;
    private final int telegramPerSecond;
    private final Map<String, Instant> nextSlot = new HashMap<>();

    @Autowired
    RateLimiters(Clock clock, DeliveryProperties properties) {
        this(clock, duration -> Thread.sleep(duration), properties.telegramChatGap(),
                properties.telegramPerSecond());
    }

    RateLimiters(Clock clock, Sleeper sleeper, Duration chatGap, int telegramPerSecond) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.chatGap = chatGap;
        this.telegramPerSecond = Math.max(1, telegramPerSecond);
    }

    /** Waits for a slot of the cooperative's bot and of the member's chat. */
    void telegram(UUID tenantId, UUID memberId) throws InterruptedException {
        await(reserve(Map.of("bot:" + tenantId, gap(telegramPerSecond), "chat:" + tenantId + ":" + memberId,
                chatGap)));
    }

    /** Waits for a slot of the cooperative's mailbox at {@code perSecond} letters a second. */
    void email(UUID tenantId, int perSecond) throws InterruptedException {
        await(reserve(Map.of("mail:" + tenantId, gap(Math.max(1, perSecond)))));
    }

    /** The time between two sends at {@code perSecond} a second. */
    static Duration gap(int perSecond) {
        return Duration.ofNanos(1_000_000_000L / perSecond);
    }

    /** Reserves the next slot of every limit and answers when the latest of them comes. */
    private synchronized Instant reserve(Map<String, Duration> limits) {
        Instant now = clock.instant();
        if (nextSlot.size() > MAX_KEYS) {
            nextSlot.values().removeIf(slot -> slot.isBefore(now));
        }
        Instant at = now;
        for (String key : limits.keySet()) {
            Instant slot = nextSlot.get(key);
            if (slot != null && slot.isAfter(at)) {
                at = slot;
            }
        }
        for (Map.Entry<String, Duration> limit : limits.entrySet()) {
            nextSlot.put(limit.getKey(), at.plus(limit.getValue()));
        }
        return at;
    }

    private void await(Instant at) throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), at);
        if (!wait.isNegative() && !wait.isZero()) {
            sleeper.sleep(wait);
        }
    }
}
