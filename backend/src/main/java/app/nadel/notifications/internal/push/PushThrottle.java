package app.nadel.notifications.internal.push;

import app.nadel.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * At most one push of a throttled code per member in a rolling window (feature 021 §2.2: «Нові покази на перевірку»
 * at most once an hour per user), table {@code push_throttle} (V39), one row per cooperative, member and code. A send
 * is claimed by one conditional upsert for all the members at once: a member without a row, or whose last push of the
 * code is at least the window old, gets {@code last_sent_at = now} and is claimed; any other member is not. The row
 * lock of the upsert makes the claim atomic across transactions and instances, so two submissions at the same moment
 * claim a member once. Runs in the caller's transaction of the bound cooperative (RLS).
 */
@Component
class PushThrottle {

    private final JdbcTemplate jdbc;

    PushThrottle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims a push of the code for the members at {@code now}; the members claimed. Every member when the code is not
     * throttled.
     */
    Set<UUID> claim(PushCode code, Collection<UUID> members, Instant now) {
        Duration window = code.throttle();
        Set<UUID> distinct = new LinkedHashSet<>(members);
        if (window == null || distinct.isEmpty()) {
            return distinct;
        }
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative"));
        List<UUID> claimed = jdbc.queryForList("insert into push_throttle (tenant_id, member_id, topic, last_sent_at) "
                + "select ?, member, ?, ? from unnest(?::uuid[]) as member "
                + "on conflict (tenant_id, member_id, topic) do update set last_sent_at = excluded.last_sent_at "
                + "where push_throttle.last_sent_at <= ? returning member_id", UUID.class, tenantId, code.name(),
                Timestamp.from(now), array(distinct), Timestamp.from(now.minus(window)));
        return new LinkedHashSet<>(claimed);
    }

    private static String array(Collection<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }
}
