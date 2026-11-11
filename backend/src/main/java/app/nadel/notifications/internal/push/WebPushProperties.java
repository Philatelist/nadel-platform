package app.nadel.notifications.internal.push;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code nadel.webpush.*}: the VAPID key pair and how push messages are sent (feature 021 §2.2, ADR 0021).
 *
 * @param publicKey           the VAPID public key ({@code NADEL_WEBPUSH_PUBLIC_KEY}): an uncompressed P-256 point,
 *                            base64url without padding (87 characters)
 * @param privateKey          the VAPID private key ({@code NADEL_WEBPUSH_PRIVATE_KEY}): the 32-byte scalar, base64url
 *                            without padding (43 characters). A secret: never logged
 * @param subject             the VAPID contact ({@code NADEL_WEBPUSH_SUBJECT}): a {@code mailto:} or {@code https:} URL
 * @param allowedHosts        the push services an endpoint may point at: a host name, or {@code *.} and a domain for
 *                            its subdomains; https only
 * @param extraAllowedOrigins further origins ({@code scheme://host:port}) an endpoint may point at, also over http and
 *                            on a private address: the fake push service of development and the tests' in-JVM fake;
 *                            empty in production
 * @param connectTimeout      how long to wait for a connection to a push service (5 s)
 * @param requestTimeout      how long to wait for its answer (10 s)
 * @param retryDelays         the pauses before the next attempts after a failure the service may recover from (429,
 *                            5xx, no connection); the last one repeats until the time to live is over
 * @param ttl                 how long a push may still be delivered after its event (3 days): the push service's
 *                            {@code TTL} and the end of the retries
 */
@ConfigurationProperties("nadel.webpush")
record WebPushProperties(@Nullable String publicKey, @Nullable String privateKey, @Nullable String subject,
        @Nullable List<String> allowedHosts, @Nullable List<String> extraAllowedOrigins,
        @Nullable Duration connectTimeout, @Nullable Duration requestTimeout, @Nullable List<Duration> retryDelays,
        @Nullable Duration ttl) {

    /** The push services of the browsers in use: Chrome and Edge's FCM, Firefox, Windows, Safari. */
    static final List<String> DEFAULT_HOSTS = List.of("fcm.googleapis.com", "*.push.services.mozilla.com",
            "*.notify.windows.com", "web.push.apple.com");

    WebPushProperties {
        allowedHosts = allowedHosts == null ? DEFAULT_HOSTS : List.copyOf(allowedHosts);
        extraAllowedOrigins = extraAllowedOrigins == null ? List.of()
                : extraAllowedOrigins.stream().filter(origin -> origin != null && !origin.isBlank()).toList();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        retryDelays = retryDelays == null || retryDelays.isEmpty()
                ? List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
                        Duration.ofHours(6))
                : List.copyOf(retryDelays);
        ttl = ttl == null ? Duration.ofDays(3) : ttl;
    }

    /** Settings without keys (tests). */
    static WebPushProperties withoutKeys() {
        return new WebPushProperties(null, null, null, null, null, null, null, null, null);
    }

    /** The same settings with these keys and subject (tests). */
    WebPushProperties withKeys(@Nullable String newPublicKey, @Nullable String newPrivateKey,
            @Nullable String newSubject) {
        return new WebPushProperties(newPublicKey, newPrivateKey, newSubject, allowedHosts, extraAllowedOrigins,
                connectTimeout, requestTimeout, retryDelays, ttl);
    }

    /** The same settings with these extra origins (tests). */
    WebPushProperties withExtraOrigins(List<String> origins) {
        return new WebPushProperties(publicKey, privateKey, subject, allowedHosts, origins, connectTimeout,
                requestTimeout, retryDelays, ttl);
    }

    /** Never prints the keys. */
    @Override
    public String toString() {
        return "WebPushProperties[allowedHosts=" + allowedHosts + ", extraAllowedOrigins=" + extraAllowedOrigins + "]";
    }

    /** Binds {@link WebPushProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebPushProperties.class)
    static class WebPushConfiguration {
    }
}
