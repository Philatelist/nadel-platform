package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.nadel.notifications.internal.push.PushSubscriptions.Target;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * One attempt of a push job without a database or a push service: what is dropped before the device is opened (an
 * unknown code, push off, the time to live over, the code's own shorter one included), what is never sent (a message
 * that cannot be built, an endpoint that is not a URI, logged without the endpoint), and what becomes of a retry (the
 * next delay or a longer {@code Retry-After}, dropped when past the time to live, reported when JobRunr does not take
 * it) and of refusals (the fifth deletes the device).
 */
@ExtendWith(OutputCaptureExtension.class)
class PushSenderTests {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/secret-endpoint-token";

    private final UUID tenant = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final VapidKeys keys = keys();
    private final PushSubscriptions subscriptions = mock(PushSubscriptions.class);
    private final WebPushClient client = mock(WebPushClient.class);
    private final PushQueue queue = mock(PushQueue.class);
    private final PushSender sender = sender(keys);

    private static VapidKeys keys() {
        KeyPair pair = P256.generate();
        return new VapidKeys(P256.base64Url(P256.encode((ECPublicKey) pair.getPublic())),
                P256.base64Url(P256.encode((ECPrivateKey) pair.getPrivate())), "mailto:push@nadel.test");
    }

    private PushSender sender(VapidKeys vapid) {
        return new PushSender(vapid, subscriptions, client, queue, WebPushProperties.withoutKeys(),
                Clock.fixed(NOW, ZoneOffset.UTC), new NoDatabase());
    }

    private SendPush job(String code, @Nullable String month, int attempt, Instant queuedAt) {
        return new SendPush(tenant, deviceId, code, "build-1", month, attempt, queuedAt);
    }

    private SendPush invoices(int attempt, Instant queuedAt) {
        return job("INVOICES_ISSUED", "2026-11", attempt, queuedAt);
    }

    private void device(String endpoint) {
        when(subscriptions.target(deviceId)).thenReturn(Optional.of(new Target(deviceId, UUID.randomUUID(), endpoint,
                new byte[65], new byte[16], "uk", keys.serverKeyId())));
    }

    private void answer(PushOutcome outcome) {
        when(client.send(any(), any(), any(), any(), any(), any())).thenReturn(outcome);
    }

    @Test
    void anUnknownCodeOrPushOffSendsNothing() {
        assertThat(sender.send(job("NO_SUCH_CODE", null, 0, NOW))).isEqualTo("UNKNOWN_CODE");
        assertThat(sender(new VapidKeys(null, null, null)).send(invoices(0, NOW))).isEqualTo("PUSH_OFF");

        verifyNoInteractions(subscriptions, client, queue);
    }

    @Test
    void aMessagePastItsTimeToLiveIsDroppedBeforeTheDeviceIsOpened() {
        assertThat(sender.send(invoices(0, NOW.minus(Duration.ofDays(3))))).isEqualTo("EXPIRED");
        // the code's own time to live (an hour) is shorter than nadel.webpush.ttl
        assertThat(sender.send(job("READINGS_TO_APPROVE", null, 0, NOW.minus(Duration.ofHours(1)))))
                .isEqualTo("EXPIRED");
        verifyNoInteractions(subscriptions, client, queue);

        assertThat(sender.send(job("READINGS_TO_APPROVE", null, 0, NOW.minus(Duration.ofMinutes(59)))))
                .isEqualTo("NO_DEVICE");
        verify(subscriptions).target(deviceId);
    }

    @Test
    void aMessageThatCannotBeBuiltIsNotSentAndTheLogNeverNamesTheEndpoint(CapturedOutput output) {
        device(ENDPOINT);
        assertThat(sender.send(job("INVOICES_ISSUED", null, 0, NOW))).isEqualTo("NOT_SENDABLE");

        device(ENDPOINT + " with spaces");
        assertThat(sender.send(invoices(0, NOW))).isEqualTo("NOT_SENDABLE");

        verifyNoInteractions(client, queue);
        assertThat(output.getAll()).contains("NOT_SENDABLE").doesNotContain("secret-endpoint-token");
    }

    @Test
    void aRetryGoesAfterTheNextDelayOrALongerRetryAfter() {
        device(ENDPOINT);
        when(queue.schedule(any(), any())).thenReturn(true);
        Instant queuedAt = NOW.minus(Duration.ofHours(1));

        answer(PushOutcome.of(503, null, NOW));
        assertThat(sender.send(invoices(0, queuedAt))).isEqualTo("RETRY_SCHEDULED");
        verify(queue).schedule(invoices(1, queuedAt), NOW.plus(Duration.ofMinutes(1)));

        answer(PushOutcome.of(429, "600", NOW));
        assertThat(sender.send(invoices(1, queuedAt))).isEqualTo("RETRY_SCHEDULED");
        verify(queue).schedule(invoices(2, queuedAt), NOW.plus(Duration.ofMinutes(10)));

        // the last delay repeats
        answer(PushOutcome.unreachable());
        assertThat(sender.send(invoices(9, queuedAt))).isEqualTo("RETRY_SCHEDULED");
        verify(queue).schedule(invoices(10, queuedAt), NOW.plus(Duration.ofHours(6)));
        // the push service keeps the message for what is left of the 3 days
        verify(client, times(3)).send(eq(URI.create(ENDPOINT)), any(), any(), any(), eq(Duration.ofHours(71)),
                isNull());
    }

    @Test
    void aRetryPastTheTimeToLiveDropsTheMessage() {
        device(ENDPOINT);
        answer(PushOutcome.unreachable());

        // the first delay is a minute, and only 30 seconds of the 3 days are left
        assertThat(sender.send(invoices(0, NOW.minus(Duration.ofDays(3)).plusSeconds(30))))
                .isEqualTo("RETRY_EXPIRED");

        verifyNoInteractions(queue);
    }

    @Test
    void aRetryThatJobRunrDoesNotTakeIsReported() {
        device(ENDPOINT);
        answer(PushOutcome.of(500, null, NOW));
        when(queue.schedule(any(), any())).thenReturn(false);

        assertThat(sender.send(invoices(0, NOW))).isEqualTo("RETRY_NOT_QUEUED");
    }

    @Test
    void theFifthRefusalInARowDeletesTheDevice() {
        device(ENDPOINT);
        answer(PushOutcome.of(400, null, NOW));
        when(subscriptions.rejected(deviceId)).thenReturn(false, true);

        assertThat(sender.send(invoices(0, NOW))).isEqualTo("REJECTED");
        assertThat(sender.send(invoices(0, NOW))).isEqualTo("REJECTED_DELETED");
        verifyNoInteractions(queue);
    }

    /** Runs the work at once: there is no database in these tests. */
    private static final class NoDatabase implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // nothing to commit
        }

        @Override
        public void rollback(TransactionStatus status) {
            // nothing to roll back
        }
    }
}
