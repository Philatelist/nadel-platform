package app.nadel.notifications.internal.push;

import app.nadel.notifications.internal.push.PushSubscriptions.Target;
import app.nadel.tenancy.TenantContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One attempt of a push message to one device (the body of the JobRunr job {@link SendPush}, feature 021 §2.2): binds
 * the job's cooperative ({@code TenantContext.runAs}), opens the device in a short read-only transaction, sends outside
 * any transaction ({@link WebPushClient}) and records the outcome in a short transaction:
 * <ul>
 *   <li>delivered: {@code last_sent_at} noted, the refusals cleared;</li>
 *   <li>gone (404, 410): the device deleted, never tried again;</li>
 *   <li>refused (another 4xx, or an endpoint that is no longer allowed): one more refusal; the fifth in a row deletes
 *       the device;</li>
 *   <li>retry (429, 5xx, no answer): the next attempt scheduled as a new job after the next of
 *       {@code nadel.webpush.retry-delays} (the last one repeating), or later when the service's {@code Retry-After}
 *       asks for more, while the time to live ({@link PushCode#ttl} of the code, else {@code nadel.webpush.ttl}, 3 days
 *       from the first attempt) lasts; after it the message is dropped.</li>
 * </ul>
 * A device made with another VAPID key pair (after a rotation) can no longer receive anything and is deleted; a device
 * deleted meanwhile is skipped. While push is off nothing is sent. Logs carry the cooperative, the member, the code
 * and the outcome only: never the endpoint, a key or the message.
 */
@Component
class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    private final VapidKeys keys;
    private final PushSubscriptions subscriptions;
    private final WebPushClient client;
    private final PushQueue queue;
    private final WebPushProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    PushSender(VapidKeys keys, PushSubscriptions subscriptions, WebPushClient client, PushQueue queue,
            WebPushProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.subscriptions = subscriptions;
        this.client = client;
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** One attempt of the job; what came of it (the tests read it). */
    String send(SendPush job) {
        PushCode code;
        try {
            code = PushCode.valueOf(job.code());
        } catch (IllegalArgumentException e) {
            log.warn("Push job of cooperative {} has an unknown code", job.tenantId());
            return "UNKNOWN_CODE";
        }
        if (!keys.enabled()) {
            log.info("Push {} for cooperative {} dropped: push is off", code, job.tenantId());
            return "PUSH_OFF";
        }
        return TenantContext.runAs(job.tenantId(), () -> attempt(job, code));
    }

    private String attempt(SendPush job, PushCode code) {
        Instant now = clock.instant();
        Duration ttl = code.ttl() != null ? code.ttl() : properties.ttl();
        Instant deadline = job.queuedAt().plus(ttl);
        if (!now.isBefore(deadline)) {
            log.info("Push {} for cooperative {}: EXPIRED", code, job.tenantId());
            return "EXPIRED";
        }
        Optional<Target> found = readOnly.execute(status -> subscriptions.target(job.subscriptionId()));
        if (found == null || found.isEmpty()) {
            log.debug("Push {} for cooperative {}: the device no longer exists", code, job.tenantId());
            return "NO_DEVICE";
        }
        Target target = found.get();
        if (!Arrays.equals(target.serverKeyId(), keys.serverKeyId())) {
            transaction.executeWithoutResult(status -> subscriptions.delete(target.id()));
            return logged(code, target, job, "OTHER_KEY_DELETED");
        }
        byte[] payload;
        URI endpoint;
        try {
            payload = PushPayload.of(code, params(code, job), target.language());
            endpoint = new URI(target.endpoint());
        } catch (IllegalArgumentException | URISyntaxException e) {
            return logged(code, target, job, "NOT_SENDABLE");
        }
        PushOutcome outcome = client.send(endpoint, target.p256dh(), target.auth(), payload,
                Duration.between(now, deadline), code.topic());
        String result = switch (outcome.kind()) {
            case DELIVERED -> {
                transaction.executeWithoutResult(status -> subscriptions.delivered(target.id(), clock.instant()));
                yield "DELIVERED";
            }
            case GONE -> {
                transaction.executeWithoutResult(status -> subscriptions.delete(target.id()));
                yield "GONE_DELETED";
            }
            case REJECTED -> {
                Boolean deleted = transaction.execute(status -> subscriptions.rejected(target.id()));
                yield Boolean.TRUE.equals(deleted) ? "REJECTED_DELETED" : "REJECTED";
            }
            case RETRY -> {
                Optional<Instant> next = nextAttempt(job.attempt(), job.queuedAt(), clock.instant(),
                        outcome.retryAfter(), properties.retryDelays(), ttl);
                if (next.isEmpty()) {
                    yield "RETRY_EXPIRED";
                }
                yield queue.schedule(job.next(), next.get()) ? "RETRY_SCHEDULED" : "RETRY_NOT_QUEUED";
            }
        };
        return logged(code, target, job, outcome.status() == 0 ? result : result + " " + outcome.status());
    }

    private static String logged(PushCode code, Target target, SendPush job, String outcome) {
        log.info("Push {} to a device of member {} of cooperative {}: {}", code, target.memberId(), job.tenantId(),
                outcome);
        return outcome.split(" ")[0];
    }

    /** The parameters of the code from the job. */
    private static Map<String, String> params(PushCode code, SendPush job) {
        if (code.params().isEmpty()) {
            return Map.of();
        }
        if (job.month() == null) {
            throw new IllegalArgumentException("The job has no month");
        }
        return Map.of("month", job.month());
    }

    /**
     * When the next attempt goes: after the delay of this attempt ({@code delays[attempt]}, the last delay for every
     * later attempt), or after {@code retryAfter} when it is longer; empty when that is past the time to live.
     *
     * @param attempt the attempt that just failed, 0 for the first
     */
    static Optional<Instant> nextAttempt(int attempt, Instant queuedAt, Instant now, @Nullable Duration retryAfter,
            List<Duration> delays, Duration ttl) {
        Duration delay = delays.get(Math.min(Math.max(attempt, 0), delays.size() - 1));
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        Instant at = now.plus(delay);
        return at.isBefore(queuedAt.plus(ttl)) ? Optional.of(at) : Optional.empty();
    }
}
