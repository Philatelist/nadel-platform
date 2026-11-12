package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.internal.push.PushOutcome.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a push service's answer means (RFC 8030): 2xx delivered, 404 and 410 gone, 429 and 5xx tried again (after
 * {@code Retry-After} in seconds or as a date), any other answer refused; and when the next attempt goes, until the
 * time to live is over.
 */
class PushOutcomeTests {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final List<Duration> DELAYS = WebPushProperties.withoutKeys().retryDelays();
    private static final Duration TTL = Duration.ofDays(3);

    @Test
    void theStatusesMapToTheOutcomes() {
        assertThat(PushOutcome.of(201, null, NOW).kind()).isEqualTo(Kind.DELIVERED);
        assertThat(PushOutcome.of(200, null, NOW).kind()).isEqualTo(Kind.DELIVERED);
        assertThat(PushOutcome.of(410, null, NOW).kind()).isEqualTo(Kind.GONE);
        assertThat(PushOutcome.of(404, null, NOW).kind()).isEqualTo(Kind.GONE);
        assertThat(PushOutcome.of(429, null, NOW).kind()).isEqualTo(Kind.RETRY);
        assertThat(PushOutcome.of(500, null, NOW).kind()).isEqualTo(Kind.RETRY);
        assertThat(PushOutcome.of(503, null, NOW).kind()).isEqualTo(Kind.RETRY);
        for (int status : new int[] {301, 400, 401, 403, 413}) {
            assertThat(PushOutcome.of(status, null, NOW).kind()).as("%s", status).isEqualTo(Kind.REJECTED);
        }
        assertThat(PushOutcome.unreachable().kind()).isEqualTo(Kind.RETRY);
        assertThat(PushOutcome.notAllowed().kind()).isEqualTo(Kind.REJECTED);
    }

    @Test
    void retryAfterIsReadInSecondsOrAsADate() {
        assertThat(PushOutcome.of(429, "120", NOW).retryAfter()).isEqualTo(Duration.ofSeconds(120));
        assertThat(PushOutcome.of(503, "Thu, 12 Nov 2026 10:30:00 GMT", NOW).retryAfter())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(PushOutcome.retryAfter("Thu, 12 Nov 2026 09:00:00 GMT", NOW)).isNull();
        assertThat(PushOutcome.retryAfter("soon", NOW)).isNull();
        assertThat(PushOutcome.retryAfter("0", NOW)).isNull();
        assertThat(PushOutcome.retryAfter(null, NOW)).isNull();
        assertThat(PushOutcome.of(410, "120", NOW).retryAfter()).isNull();
    }

    @Test
    void theRetriesFollowTheDelaysAndTheLastOneRepeats() {
        Instant queued = NOW;
        assertThat(PushSender.nextAttempt(0, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(60));
        assertThat(PushSender.nextAttempt(1, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(300));
        assertThat(PushSender.nextAttempt(2, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(1800));
        assertThat(PushSender.nextAttempt(3, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(7200));
        assertThat(PushSender.nextAttempt(4, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(21600));
        assertThat(PushSender.nextAttempt(9, queued, NOW, null, DELAYS, TTL)).contains(NOW.plusSeconds(21600));
    }

    @Test
    void aLongerRetryAfterWinsAndTheTimeToLiveEndsTheRetries() {
        assertThat(PushSender.nextAttempt(0, NOW, NOW, Duration.ofMinutes(10), DELAYS, TTL))
                .contains(NOW.plus(Duration.ofMinutes(10)));
        assertThat(PushSender.nextAttempt(0, NOW, NOW, Duration.ofSeconds(5), DELAYS, TTL))
                .contains(NOW.plus(Duration.ofMinutes(1)));
        Instant late = NOW.plus(TTL).minus(Duration.ofHours(1));
        assertThat(PushSender.nextAttempt(4, NOW, late, null, DELAYS, TTL)).isEqualTo(Optional.empty());
        assertThat(PushSender.nextAttempt(0, NOW, NOW, Duration.ofDays(4), DELAYS, TTL)).isEmpty();
        // the approvals push of a later slice lives an hour
        assertThat(PushSender.nextAttempt(2, NOW, NOW.plusSeconds(400), null, DELAYS, Duration.ofHours(1)))
                .contains(NOW.plusSeconds(2200));
        assertThat(PushSender.nextAttempt(3, NOW, NOW.plusSeconds(2200), null, DELAYS, Duration.ofHours(1))).isEmpty();
    }
}
