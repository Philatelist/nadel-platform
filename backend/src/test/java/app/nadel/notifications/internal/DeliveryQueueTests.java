package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Waking a send lane (feature 017 §2.4): at once outside a transaction, only after the commit inside one, as a job
 * whose id is fixed per lane and second (so wakes of one second make one job), and a wake the job storage refuses is
 * left to the sweep instead of failing the caller.
 */
class DeliveryQueueTests {

    private static final UUID TENANT = UUID.fromString("22222222-0000-0000-0000-000000000017");
    private static final Instant NOW = Instant.parse("2026-10-30T10:15:30Z");

    private final JobRequestScheduler jobs = mock(JobRequestScheduler.class);
    private final DeliveryQueue queue = new DeliveryQueue(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void outsideATransactionTheLaneIsWokenAtOnceAndWakesOfOneSecondMakeOneJob() {
        queue.wakeAfterCommit(TENANT, Channel.EMAIL);
        queue.wake(TENANT, Channel.EMAIL);

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(jobs, org.mockito.Mockito.times(2)).enqueue(ids.capture(), eq(new DispatchDeliveries(TENANT, "EMAIL")));
        assertThat(ids.getAllValues()).hasSize(2).containsOnly(ids.getValue());
    }

    @Test
    void insideATransactionTheLaneIsWokenOnlyAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            queue.wakeAfterCommit(TENANT, Channel.TELEGRAM);
            verifyNoInteractions(jobs);

            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(jobs).enqueue(any(UUID.class), eq(new DispatchDeliveries(TENANT, "TELEGRAM")));
    }

    @Test
    void aLaterRunIsScheduledAndAPastOneRunsNow() {
        queue.schedule(TENANT, Channel.EMAIL, NOW.plus(Duration.ofMinutes(5)));
        queue.schedule(TENANT, Channel.EMAIL, NOW.minus(Duration.ofMinutes(5)));

        verify(jobs).schedule(any(UUID.class), eq(NOW.plus(Duration.ofMinutes(5))),
                eq(new DispatchDeliveries(TENANT, "EMAIL")));
        verify(jobs).enqueue(any(UUID.class), eq(new DispatchDeliveries(TENANT, "EMAIL")));
    }

    @Test
    void aWakeTheJobStorageRefusesIsLeftToTheSweep() {
        when(jobs.enqueue(any(UUID.class), any(JobRequest.class))).thenThrow(new IllegalStateException("down"));
        when(jobs.schedule(any(UUID.class), any(Instant.class), any(JobRequest.class)))
                .thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> queue.wakeAfterCommit(TENANT, Channel.EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> queue.schedule(TENANT, Channel.EMAIL, NOW.plusSeconds(60))).doesNotThrowAnyException();
    }
}
