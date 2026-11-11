package app.nadel.notifications.internal.push;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;
import org.jspecify.annotations.Nullable;

/**
 * The JobRunr job that sends one push message to one device (feature 021 §2.2, ADR 0007): run by
 * {@link SendPushHandler}. Its arguments are ids, codes, the month and times only, so the job table holds no
 * cooperative data (no endpoint, no key, no name). Its id comes from everything but the times
 * ({@link PushQueue#jobId}), so an event delivered twice creates no second job and each retry is one job.
 *
 * @param tenantId       the cooperative
 * @param subscriptionId the device ({@code push_subscription.id})
 * @param code           the {@link PushCode}
 * @param ref            what the message is about, unique per message of the code (the build of an
 *                       {@code INVOICES_ISSUED})
 * @param month          the {@code month} parameter ({@code YYYY-MM}) of a code that has one, else null
 * @param attempt        0 for the first attempt, then one more per retry
 * @param queuedAt       when the first attempt was queued: the time to live counts from it
 */
public record SendPush(UUID tenantId, UUID subscriptionId, String code, String ref, @Nullable String month,
        int attempt, Instant queuedAt) implements JobRequest {

    public SendPush {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(queuedAt, "queuedAt");
    }

    /** The same message's next attempt. */
    SendPush next() {
        return new SendPush(tenantId, subscriptionId, code, ref, month, attempt + 1, queuedAt);
    }

    @Override
    public Class<SendPushHandler> getJobRequestHandler() {
        return SendPushHandler.class;
    }
}
