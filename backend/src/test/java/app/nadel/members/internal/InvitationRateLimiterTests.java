package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The sliding window of {@link InvitationRateLimiter} with a clock the test moves: per member five sent invitations,
 * per address thirty (here three, to keep the test short) and five failed links per fifteen minutes; the window frees
 * one slot when the oldest counted event leaves it, a refusal is not counted, the keys are apart, and the number of
 * keys is bounded. A usable link never reaches the limiter (see {@link InvitationRateLimitTests}).
 */
class InvitationRateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String ADDRESS = "203.0.113.7";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-06T10:00:00Z"));
    private final InvitationRateLimiter limiter = new InvitationRateLimiter(5, 30, WINDOW, 1000, clock);

    private static void assertTooMany(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo(ProblemCodes.TOO_MANY_REQUESTS);
        });
    }

    private static void send(InvitationRateLimiter limiter, String address, UUID member) {
        limiter.ensureSendAllowed(address, TENANT, member);
        limiter.recordSend(address, TENANT, member);
    }

    @Test
    void theSixthFailedLinkFromOneAddressIsRefusedAndOtherAddressesAreNot() {
        for (int i = 0; i < 5; i++) {
            limiter.countFailedLink(ADDRESS);
            clock.advance(Duration.ofMinutes(1));
        }
        assertTooMany(() -> limiter.countFailedLink(ADDRESS));
        assertThatCode(() -> limiter.countFailedLink("203.0.113.8")).doesNotThrowAnyException();
        // failed links and sends are counted apart
        assertThatCode(() -> send(limiter, ADDRESS, UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void theSixthSendForOneMemberIsRefusedFromAnyAddress() {
        UUID member = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            send(limiter, "198.51.100." + i, member);
        }
        assertTooMany(() -> limiter.ensureSendAllowed("198.51.100.99", TENANT, member));
        // another member, and the same member id in another cooperative, are apart
        assertThatCode(() -> send(limiter, "198.51.100.99", UUID.randomUUID())).doesNotThrowAnyException();
        assertThatCode(() -> limiter.ensureSendAllowed("198.51.100.99", UUID.randomUUID(), member))
                .doesNotThrowAnyException();
    }

    @Test
    void oneAddressSendsToManyMembersUpToItsOwnLimit() {
        InvitationRateLimiter three = new InvitationRateLimiter(5, 3, WINDOW, 1000, clock);
        for (int i = 0; i < 3; i++) {
            send(three, ADDRESS, UUID.randomUUID());
        }
        assertTooMany(() -> three.ensureSendAllowed(ADDRESS, TENANT, UUID.randomUUID()));
        assertThatCode(() -> send(three, "203.0.113.8", UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void theWindowFreesASlotWhenTheOldestEventLeavesIt() {
        UUID member = UUID.randomUUID();
        Instant first = clock.instant();
        send(limiter, ADDRESS, member);
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 4; i++) {
            send(limiter, ADDRESS, member);
        }
        assertTooMany(() -> limiter.ensureSendAllowed(ADDRESS, TENANT, member));

        // a moment before the first send is fifteen minutes old: still refused, and the refusal is not counted
        clock.set(first.plus(WINDOW).minusMillis(1));
        assertTooMany(() -> limiter.ensureSendAllowed(ADDRESS, TENANT, member));
        // the first send leaves the window: one slot, then refused again until the other four leave
        clock.set(first.plus(WINDOW));
        send(limiter, ADDRESS, member);
        assertTooMany(() -> limiter.ensureSendAllowed(ADDRESS, TENANT, member));
        clock.set(first.plus(Duration.ofMinutes(5)).plus(WINDOW));
        for (int i = 0; i < 4; i++) {
            send(limiter, ADDRESS, member);
        }
        assertTooMany(() -> limiter.ensureSendAllowed(ADDRESS, TENANT, member));
    }

    @Test
    void aBlockedAddressIsFreedOneWindowAfterItsOldestFailedLink() {
        for (int i = 0; i < 5; i++) {
            limiter.countFailedLink(ADDRESS);
        }
        for (int i = 0; i < 10; i++) {
            assertTooMany(() -> limiter.countFailedLink(ADDRESS)); // refusals are not counted
        }
        clock.advance(WINDOW);
        assertThatCode(() -> limiter.countFailedLink(ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    void theLeastRecentlyUsedKeyIsDroppedPastTheBound() {
        InvitationRateLimiter small = new InvitationRateLimiter(1, 1, WINDOW, 2, clock);
        small.countFailedLink("a");
        small.countFailedLink("b");
        assertTooMany(() -> small.countFailedLink("a")); // "a" is now the most recent
        small.countFailedLink("c"); // drops "b"

        assertTooMany(() -> small.countFailedLink("a"));
        assertTooMany(() -> small.countFailedLink("c"));
        assertThatCode(() -> small.countFailedLink("b")).doesNotThrowAnyException();
    }

    @Test
    void theSettingsMustBePositive() {
        assertThatThrownBy(() -> new InvitationRateLimiter(0, 30, WINDOW, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, 0, WINDOW, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, 30, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, 30, WINDOW.negated(), 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationRateLimiter(5, 30, WINDOW, 0, clock))
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
