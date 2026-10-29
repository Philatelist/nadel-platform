package app.nadel.notifications.internal.telegram;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code nadel.telegram.*}: where the Telegram Bot API is and how long to wait for it.
 *
 * @param apiUrl         the Bot API base URL ({@code NADEL_TELEGRAM_API_URL}): {@code https://api.telegram.org} by
 *                       default, the fake server of {@code infra/fake-telegram} ({@code http://localhost:8091}) in the
 *                       dev profile, an in-JVM fake in tests
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout    how long to wait for the answer of an ordinary call
 */
@ConfigurationProperties("nadel.telegram")
public record TelegramProperties(URI apiUrl, @Nullable Duration connectTimeout, @Nullable Duration readTimeout) {

    public TelegramProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
    }
}
