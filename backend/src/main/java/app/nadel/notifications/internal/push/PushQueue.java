package app.nadel.notifications.internal.push;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts {@link SendPush} jobs into JobRunr with a deterministic id ({@link #jobId}): the cooperative, the device, the
 * code, the reference and the attempt. JobRunr skips an id it already has, so a redelivered event or a run repeated
 * after a crash queues nothing new, as for the delivery lanes ({@code DeliveryQueue}).
 */
@Component
class PushQueue {

    private static final Logger log = LoggerFactory.getLogger(PushQueue.class);

    private final JobRequestScheduler jobs;
    private final Clock clock;

    PushQueue(JobRequestScheduler jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /** The job's id: the name-based UUID of its cooperative, device, code, reference and attempt. */
    static UUID jobId(SendPush job) {
        return UUID.nameUUIDFromBytes(("push:" + job.tenantId() + ":" + job.subscriptionId() + ":" + job.code() + ":"
                + job.ref() + ":" + job.attempt()).getBytes(StandardCharsets.UTF_8));
    }

    /** Runs the job as soon as a worker is free. Whether it was handed to JobRunr. */
    boolean enqueue(SendPush job) {
        return schedule(job, clock.instant());
    }

    /** Runs the job at {@code at} (now when it is past). Whether it was handed to JobRunr. */
    boolean schedule(SendPush job, Instant at) {
        Instant now = clock.instant();
        try {
            if (at.isAfter(now)) {
                jobs.schedule(jobId(job), at, job);
            } else {
                jobs.enqueue(jobId(job), job);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Push {} for cooperative {} not queued: {}", job.code(), job.tenantId(),
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
