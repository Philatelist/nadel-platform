package app.nadel.imports.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** The system clock plus an offset a test can move forward, to let a preview expire without waiting. */
final class MutableClock extends Clock {

    private final Clock base;
    private volatile Duration offset = Duration.ZERO;

    MutableClock(ZoneId zone) {
        this(Clock.system(zone));
    }

    private MutableClock(Clock base) {
        this.base = base;
    }

    void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock clock = new MutableClock(base.withZone(zone));
        clock.offset = offset;
        return clock;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }
}
