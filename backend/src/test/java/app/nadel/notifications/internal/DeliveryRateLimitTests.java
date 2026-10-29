package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The outbound pace (feature 017 §2.4, {@link RateLimiters}): one message a second to one member's chat, about 20 a
 * second through one bot, the cooperative's e-mail rate through its mailbox; cooperatives do not share a limit. The
 * clock is the test's and a pause only moves it, so nothing waits.
 */
class DeliveryRateLimitTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    private final DeliveryTestSupport.MovableClock clock =
            new DeliveryTestSupport.MovableClock(Instant.parse("2026-11-05T09:00:00Z"));
    private final List<Duration> pauses = new ArrayList<>();
    private final RateLimiters limiters = new RateLimiters(clock, pause -> {
        pauses.add(pause);
        clock.advance(pause);
    }, Duration.ofSeconds(1), 20);

    @Test
    void oneMemberGetsAtMostOneMessageASecond() throws Exception {
        UUID member = UUID.randomUUID();
        Instant start = clock.instant();

        limiters.telegram(TENANT, member);
        limiters.telegram(TENANT, member);
        limiters.telegram(TENANT, member);

        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(clock.instant()).isEqualTo(start.plusSeconds(2));
    }

    @Test
    void oneBotSendsAtMostTwentyMessagesASecond() throws Exception {
        Instant start = clock.instant();

        for (int i = 0; i < 41; i++) {
            limiters.telegram(TENANT, UUID.randomUUID());
        }

        // 41 messages to different members: 50 ms apart, the last one 2 s after the first
        assertThat(clock.instant()).isEqualTo(start.plusSeconds(2));
        assertThat(pauses).allSatisfy(pause -> assertThat(pause).isEqualTo(Duration.ofMillis(50)));
    }

    @Test
    void theMailboxSendsAtTheCooperativesRate() throws Exception {
        Instant start = clock.instant();

        for (int i = 0; i < 3; i++) {
            limiters.email(TENANT, 1);
        }
        assertThat(clock.instant()).isEqualTo(start.plusSeconds(2));

        Instant faster = clock.instant().plusSeconds(10);
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 5; i++) {
            limiters.email(TENANT, 4);
        }
        assertThat(clock.instant()).isEqualTo(faster.plusMillis(1000));
    }

    @Test
    void cooperativesDoNotShareALimit() throws Exception {
        UUID member = UUID.randomUUID();

        limiters.telegram(TENANT, member);
        limiters.telegram(OTHER_TENANT, member);
        limiters.email(TENANT, 1);
        limiters.email(OTHER_TENANT, 1);

        assertThat(pauses).isEmpty();
    }

    @Test
    void theRealPauseIsUsedOutsideTheTests() {
        RateLimiters real = new RateLimiters(Clock.systemUTC(), DeliveryProperties.defaults());

        assertThat(real).isNotNull();
        assertThat(RateLimiters.gap(20)).isEqualTo(Duration.ofMillis(50));
        assertThat(RateLimiters.gap(3)).isEqualTo(Duration.ofNanos(333_333_333));
    }
}
