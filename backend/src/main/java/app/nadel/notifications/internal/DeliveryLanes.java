package app.nadel.notifications.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The leases of the send lanes (table {@code delivery_lane}, one row per cooperative and channel): one sender per lane
 * across all instances at a time, so the pace of a mailbox or a bot is kept even with several instances. Each method
 * runs in the caller's short transaction of the bound cooperative (RLS); times are the database's, so every instance
 * compares leases on one clock.
 */
@Component
class DeliveryLanes {

    private final JdbcTemplate jdbc;

    DeliveryLanes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims or renews the lane for {@code owner}: only when the owner holds it, nobody does or the holder's lease ran
     * out. True when {@code owner} holds it now, for {@code lease} from now.
     */
    boolean claim(UUID tenantId, Channel channel, String owner, Duration lease) {
        jdbc.update("insert into delivery_lane (tenant_id, channel) values (?, ?) on conflict do nothing", tenantId,
                channel.name());
        return jdbc.update("""
                update delivery_lane set lease_owner = ?, lease_until = now() + ? * interval '1 millisecond'
                where tenant_id = ? and channel = ?
                  and (lease_owner = ? or lease_owner is null or lease_until is null or lease_until < now())
                """, owner, lease.toMillis(), tenantId, channel.name(), owner) == 1;
    }

    /** Gives the lane up when {@code owner} holds it. */
    void release(UUID tenantId, Channel channel, String owner) {
        jdbc.update("update delivery_lane set lease_owner = null, lease_until = null "
                + "where tenant_id = ? and channel = ? and lease_owner = ?", tenantId, channel.name(), owner);
    }
}
