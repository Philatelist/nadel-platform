package app.nadel.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wakes the send lanes through JobRunr ({@link DispatchDeliveries}). A job's id is derived from the lane and the
 * second it is due, so waking a lane twice in one second creates one job (JobRunr skips an id it has). The rows
 * themselves are the queue: a lost wake-up costs at most one sweep interval ({@link DeliveryDispatcher#sweep}).
 */
@Component
class DeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueue.class);

    private final JobRequestScheduler jobs;
    private final Clock clock;

    DeliveryQueue(JobRequestScheduler jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Runs the lane as soon as a worker is free. */
    void wake(UUID tenantId, Channel channel) {
        schedule(tenantId, channel, clock.instant());
    }

    /** Runs the lane at {@code at} (now when it is past). */
    void schedule(UUID tenantId, Channel channel, Instant at) {
        Instant now = clock.instant();
        Instant when = at.isAfter(now) ? at : now;
        UUID id = UUID.nameUUIDFromBytes(("delivery:" + tenantId + ":" + channel + ":" + when.getEpochSecond())
                .getBytes(StandardCharsets.UTF_8));
        try {
            if (when.equals(now)) {
                jobs.enqueue(id, new DispatchDeliveries(tenantId, channel.name()));
            } else {
                jobs.schedule(id, when, new DispatchDeliveries(tenantId, channel.name()));
            }
        } catch (RuntimeException e) {
            // the rows stay queued; the sweep wakes the lane
            log.warn("Delivery lane {} of cooperative {} not woken: {}", channel, tenantId,
                    e.getClass().getSimpleName());
        }
    }

    /** Wakes the lane once the caller's transaction committed (at once without one). */
    void wakeAfterCommit(UUID tenantId, Channel channel) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            wake(tenantId, channel);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wake(tenantId, channel);
            }
        });
    }
}
