package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.members.internal.InvitationRateLimiter.Endpoint;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The sliding window of {@link InvitationRateLimiter} with a clock the test moves: five requests per fifteen minutes
 * per key, the window frees one slot when the oldest admitted request leaves it, refused requests are not counted, the
 * keys (address and e-mail, each endpoint) are apart, and the number of keys is bounded.
 */
class InvitationRateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-06T10:00:00Z"));
    private final InvitationRateLimiter limiter = new InvitationRateLimiter(5, WINDOW, 1000, clock);

    private static void assertTooMany(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo(ProblemCodes.TOO_MANY_REQUESTS);
        });
    }

    @Test
    void theSixthRequestFromOneAddressWithinTheWindowIsRefused() {
        for (int i = 0; i < 5; i++) {
            limiter.acquireForAddress(Endpoint.INSPECT, "203.0.113.7");
            clock.advance(Duration.ofMinutes(1));
        }
        assertTooMany(() -> limiter.acquireForAddress(Endpoint.INSPECT, "203.0.113.7"));
        // another address, the other endpoint and an e-mail key are counted apart
        assertThatCode(() -> limiter.acquireForAddress(Endpoint.INSPECT, "203.0.113.8")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireForAddress(Endpoint.ACCEPT, "203.0.113.7")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireForEmail(Endpoint.INSPECT, "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void theWindowFreesASlotWhenTheOldestRequestLeavesIt() {
        Instant first = clock.instant();
        limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx");
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 4; i++) {
            limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx");
        }
        assertTooMany(() -> limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx"));

        // a moment before the first request is fifteen minutes old: still refused, and the refusal is not counted
        clock.set(first.plus(WINDOW).minusMillis(1));
        assertTooMany(() -> limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx"));
        // the first request leaves the window: one slot, then refused again until the other four leave
        clock.set(first.plus(WINDOW));
        limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx");
        assertTooMany(() -> limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx"));
        clock.set(first.plus(Duration.ofMinutes(5)).plus(WINDOW));
        for (int i = 0; i < 4; i++) {
            limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx");
        }
        assertTooMany(() -> limiter.acquireForEmail(Endpoint.ACCEPT, "tenant|bidx"));
    }

    @Test
    void theLeastRecentlyUsedKeyIsDroppedPastTheBound() {
        InvitationRateLimiter small = new InvitationRateLimiter(1, WINDOW, 2, clock);
        small.acquireForAddress(Endpoint.INSPECT, "a");
        small.acquireForAddress(Endpoint.INSPECT, "b");
        assertTooMany(() -> small.acquireForAddress(Endpoint.INSPECT, "a")); // "a" is now the most recent
        small.acquireForAddress(Endpoint.INSPECT, "c"); // drops "b"

        assertTooMany(() -> small.acquireForAddress(Endpoint.INSPECT, "a"));
        assertTooMany(() -> small.acquireForAddress(Endpoint.INSPECT, "c"));
        assertThatCode(() -> small.acquireForAddress(Endpoint.INSPECT, "b")).doesNotThrowAnyException();
    }

    @Test
    void theSettingsMustBePositive() {
        assertThatThrownBy(() -> new InvitationRateLimiter(0, WINDOW, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, WINDOW.negated(), 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, WINDOW, 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A clock the test sets and moves. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
