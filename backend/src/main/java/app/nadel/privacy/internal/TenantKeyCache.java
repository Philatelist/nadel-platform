package app.nadel.privacy.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Unwrapped keys per cooperative, in the memory of this instance only (never a shared cache), each kept at most
 * {@link #TTL} after it was loaded. Two threads missing at once both load; the keys they get are the same.
 */
@Component
class TenantKeyCache {

    static final Duration TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    TenantKeyCache(Clock clock) {
        this.clock = clock;
    }

    TenantKeys keys(UUID tenantId, Function<UUID, TenantKeys> loader) {
        Instant now = clock.instant();
        Entry entry = entries.get(tenantId);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return entry.keys();
        }
        TenantKeys keys = loader.apply(tenantId);
        entries.put(tenantId, new Entry(keys, now.plus(TTL)));
        return keys;
    }

    private record Entry(TenantKeys keys, Instant expiresAt) {
    }
}
