package app.nadel.notifications.internal;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code nadel.delivery.*}: how the delivery worker sends (feature 017 §2.4, ADR 0018).
 *
 * @param smtpTimeout       connect, read and write timeout of a mailbox (10 s; read by the mail layer)
 * @param retryDelays       the pauses before the second and the third attempt (1 min, 5 min): three attempts in all
 * @param laneLease         how long the lease of a send lane lasts without renewal (60 s)
 * @param runLimit          how long one run of a lane sends before it hands over to a new job (60 s)
 * @param busyRetry         when a run that found its lane held elsewhere tries again (5 s)
 * @param sweepInterval     how often the lanes with due deliveries are woken (1 min), so nothing waits after a
 *                          restart or a lost job
 * @param telegramPerSecond messages a second at most through one cooperative's bot (20; Telegram allows about 30)
 * @param telegramChatGap   the least time between two messages to one member's chat (1 s)
 */
@ConfigurationProperties("nadel.delivery")
record DeliveryProperties(@Nullable Duration smtpTimeout, @Nullable List<Duration> retryDelays,
        @Nullable Duration laneLease, @Nullable Duration runLimit, @Nullable Duration busyRetry,
        @Nullable Duration sweepInterval, @Nullable Integer telegramPerSecond, @Nullable Duration telegramChatGap) {

    /** Every attempt a delivery gets before it fails: now, then after each retry delay. */
    static final int ATTEMPTS = 3;

    DeliveryProperties {
        smtpTimeout = smtpTimeout == null ? Duration.ofSeconds(10) : smtpTimeout;
        retryDelays = retryDelays == null || retryDelays.size() != ATTEMPTS - 1
                ? List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)) : List.copyOf(retryDelays);
        laneLease = laneLease == null ? Duration.ofSeconds(60) : laneLease;
        runLimit = runLimit == null ? Duration.ofSeconds(60) : runLimit;
        busyRetry = busyRetry == null ? Duration.ofSeconds(5) : busyRetry;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
        telegramPerSecond = telegramPerSecond == null ? 20 : telegramPerSecond;
        telegramChatGap = telegramChatGap == null ? Duration.ofSeconds(1) : telegramChatGap;
    }

    /** The defaults (tests). */
    static DeliveryProperties defaults() {
        return new DeliveryProperties(null, null, null, null, null, null, null, null);
    }

    /** Binds {@link DeliveryProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeliveryProperties.class)
    static class DeliveryConfiguration {
    }
}
