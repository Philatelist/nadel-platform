package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Handing push jobs to JobRunr: a job for the future is scheduled and one for now or the past is queued, both under
 * the job's deterministic id (each attempt its own); when JobRunr does not take a job the caller is told, and the
 * warning names the code, the cooperative and the kind of failure only, never the failure's message.
 */
@ExtendWith(OutputCaptureExtension.class)
class PushQueueTests {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final JobRequestScheduler jobs = mock(JobRequestScheduler.class);
    private final PushQueue queue = new PushQueue(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
    private final SendPush job = new SendPush(UUID.randomUUID(), UUID.randomUUID(), "PAYMENT_RECORDED", "payment-1",
            null, 0, NOW);

    @Test
    void aJobForLaterIsScheduledAndOneForNowIsQueuedUnderItsId() {
        assertThat(queue.schedule(job, NOW.plus(Duration.ofMinutes(5)))).isTrue();
        assertThat(queue.enqueue(job)).isTrue();
        assertThat(queue.schedule(job.next(), NOW.minusSeconds(1))).isTrue();

        verify(jobs).schedule(PushQueue.jobId(job), NOW.plus(Duration.ofMinutes(5)), job);
        verify(jobs).enqueue(PushQueue.jobId(job), job);
        verify(jobs).enqueue(PushQueue.jobId(job.next()), job.next());
        verifyNoMoreInteractions(jobs);
        assertThat(PushQueue.jobId(job.next())).isNotEqualTo(PushQueue.jobId(job));
        assertThat(PushQueue.jobId(new SendPush(job.tenantId(), job.subscriptionId(), job.code(), job.ref(), null, 0,
                NOW.plusSeconds(60)))).as("the times are not part of the id").isEqualTo(PushQueue.jobId(job));
    }

    @Test
    void aJobThatJobRunrDoesNotTakeIsReportedWithoutTheFailuresMessage(CapturedOutput output) {
        when(jobs.schedule(any(UUID.class), any(Temporal.class), any(JobRequest.class)))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db-internal-host/secret-detail"));
        when(jobs.enqueue(any(UUID.class), any(JobRequest.class)))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db-internal-host/secret-detail"));

        assertThat(queue.schedule(job, NOW.plus(Duration.ofMinutes(5)))).isFalse();
        assertThat(queue.enqueue(job)).isFalse();

        assertThat(output.getAll()).contains("Push PAYMENT_RECORDED for cooperative " + job.tenantId()
                + " not queued: IllegalStateException").doesNotContain("secret-detail");
    }
}
