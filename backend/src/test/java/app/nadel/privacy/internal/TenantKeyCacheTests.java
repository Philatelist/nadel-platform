package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Keys stay cached for ten minutes after loading, per cooperative, and are loaded again afterwards. */
class TenantKeyCacheTests {

    static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final TenantKeyCache cache = new TenantKeyCache(clock);
    private final AtomicInteger loads = new AtomicInteger();
    private final Function<UUID, TenantKeys> loader = tenant -> {
        loads.incrementAndGet();
        return TenantKeysTests.keys(tenant, 1, TenantKeysTests.random(), TenantKeysTests.random());
    };

    @Test
    void anEntryIsReusedUntilTenMinutesHavePassed() {
        TenantKeys first = cache.keys(TENANT, loader);
        clock.advance(TenantKeyCache.TTL.minusNanos(1));
        TenantKeys stillCached = cache.keys(TENANT, loader);

        assertThat(stillCached).isSameAs(first);
        assertThat(loads).hasValue(1);

        clock.advance(Duration.ofNanos(1));
        TenantKeys reloaded = cache.keys(TENANT, loader);

        assertThat(reloaded).isNotSameAs(first);
        assertThat(loads).hasValue(2);
        clock.advance(Duration.ofMinutes(9));
        assertThat(cache.keys(TENANT, loader)).isSameAs(reloaded);
        assertThat(loads).hasValue(2);
    }

    @Test
    void cooperativesAreCachedSeparately() {
        TenantKeys tenant = cache.keys(TENANT, loader);
        TenantKeys other = cache.keys(OTHER, loader);

        assertThat(other).isNotSameAs(tenant);
        assertThat(other.tenantId()).isEqualTo(OTHER);
        assertThat(cache.keys(TENANT, loader)).isSameAs(tenant);
        assertThat(loads).hasValue(2);
        assertThat(TenantKeyCache.TTL).isEqualTo(Duration.ofMinutes(10));
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
