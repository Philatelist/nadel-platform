package app.nadel.notifications.internal.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sends one push message to a push service (RFC 8030, feature 021 §2.2) with the JDK {@link HttpClient}, never
 * following a redirect: the endpoint is checked again before every send ({@link PushEndpointPolicy#check}, with its
 * lookup), the body encrypted for the device ({@link WebPushEncryptor}), the request signed ({@link VapidSigner}) and
 * posted with {@code TTL}, {@code Content-Encoding: aes128gcm}, {@code Urgency: normal} and the code's {@code Topic}
 * when it has one. The answer becomes a
 * {@link PushOutcome}. Blocks: the caller runs it outside any transaction. Logs nothing: an endpoint is a capability
 * and the exception of a failed connection may name it, so a failure is only the outcome {@code RETRY}.
 */
@Component
class WebPushClient {

    private final HttpClient http;
    private final PushEndpointPolicy policy;
    private final WebPushEncryptor encryptor;
    private final VapidSigner signer;
    private final Duration requestTimeout;
    private final Clock clock;

    @Autowired
    WebPushClient(WebPushProperties properties, PushEndpointPolicy policy, WebPushEncryptor encryptor,
            VapidSigner signer, Clock clock) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.policy = policy;
        this.encryptor = encryptor;
        this.signer = signer;
        this.requestTimeout = properties.requestTimeout();
        this.clock = clock;
    }

    /**
     * Sends the payload to the device.
     *
     * @param endpoint the device's push address
     * @param p256dh   the device's public key (uncompressed point)
     * @param auth     the device's auth secret
     * @param payload  the plaintext message (JSON, {@link PushPayload})
     * @param ttl      how long the push service may keep the message for an offline device
     * @param topic    the {@code Topic} header (RFC 8030 §5.4: a newer message of the topic replaces this one while the
     *                 device is offline); null for none
     */
    PushOutcome send(URI endpoint, byte[] p256dh, byte[] auth, byte[] payload, Duration ttl,
            @Nullable String topic) {
        switch (policy.check(endpoint)) {
            case NOT_ALLOWED -> {
                return PushOutcome.notAllowed();
            }
            case UNRESOLVED -> {
                return PushOutcome.unreachable();
            }
            case ALLOWED -> {
                // sent below
            }
        }
        HttpRequest request;
        try {
            byte[] body = encryptor.encrypt(p256dh, auth, payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("TTL", Long.toString(Math.max(0, ttl.toSeconds())))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Urgency", "normal")
                    .header("Authorization", signer.authorization(endpoint));
            if (topic != null) {
                builder.header("Topic", topic);
            }
            request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        } catch (IllegalArgumentException e) {
            // a key that is not a point of the curve, or a URI the client does not take
            return PushOutcome.notAllowed();
        }
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return PushOutcome.of(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null),
                    clock.instant());
        } catch (IOException e) {
            return PushOutcome.unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushOutcome.unreachable();
        }
    }
}
