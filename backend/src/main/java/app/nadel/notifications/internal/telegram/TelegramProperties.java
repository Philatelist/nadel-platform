package app.nadel.notifications.internal.telegram;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code nadel.telegram.*}: where the Telegram Bot API is, how long to wait for it, and how the cooperatives' bots are
 * polled (feature 017 §2.5).
 *
 * @param apiUrl             the Bot API base URL ({@code NADEL_TELEGRAM_API_URL}): {@code https://api.telegram.org}
 *                           by default, the fake server of {@code infra/fake-telegram} ({@code http://localhost:8091})
 *                           in the dev profile, an in-JVM fake in tests
 * @param connectTimeout     how long to wait for a connection
 * @param readTimeout        how long to wait for the answer of an ordinary call
 * @param pollTimeout        how long one {@code getUpdates} long poll waits for an update (Telegram's {@code timeout},
 *                           whole seconds, 25 s by default)
 * @param botEnabled         whether this instance polls the cooperatives' bots on a schedule (default true; the tests
 *                           drive the supervisor themselves)
 * @param supervisorInterval how often the supervisor looks for bots and renews its leases (20 s)
 * @param lease              how long a lease on a cooperative's bot lasts without renewal (60 s); another instance
 *                           takes the bot over after it
 */
@ConfigurationProperties("nadel.telegram")
public record TelegramProperties(URI apiUrl, @Nullable Duration connectTimeout, @Nullable Duration readTimeout,
        @Nullable Duration pollTimeout, @Nullable Boolean botEnabled, @Nullable Duration supervisorInterval,
        @Nullable Duration lease) {

    public TelegramProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        pollTimeout = pollTimeout == null ? Duration.ofSeconds(25) : pollTimeout;
        botEnabled = botEnabled == null ? Boolean.TRUE : botEnabled;
        supervisorInterval = supervisorInterval == null ? Duration.ofSeconds(20) : supervisorInterval;
        lease = lease == null ? Duration.ofSeconds(60) : lease;
    }

    /** The same settings with another lease (the tests of the lease take over in seconds). */
    public TelegramProperties withLease(Duration newLease) {
        return new TelegramProperties(apiUrl, connectTimeout, readTimeout, pollTimeout, botEnabled, supervisorInterval,
                newLease);
    }
}
