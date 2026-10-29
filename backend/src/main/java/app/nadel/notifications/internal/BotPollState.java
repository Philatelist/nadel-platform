package app.nadel.notifications.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where the long poll of each cooperative's bot stands and which instance holds it (table {@code bot_poll_state}, one
 * row per cooperative), plus the list of cooperatives that have a bot. Every method except {@link #botTenants} runs in
 * the caller's transaction of the bound cooperative (RLS). Times are the database's, so every instance compares
 * leases on one clock.
 */
@Component
class BotPollState {

    private final JdbcTemplate jdbc;

    BotPollState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The cooperatives that have a bot token, through the {@code SECURITY DEFINER} function
     * {@code notifications_bot_tenants()} (ids only): the supervisor asks before any cooperative is bound, where RLS
     * shows no row. Runs in the caller's transaction, which binds no cooperative.
     */
    List<UUID> botTenants() {
        return jdbc.queryForList("select t from notifications_bot_tenants() as t", UUID.class);
    }

    /**
     * Claims or renews the lease of the cooperative's bot for {@code owner}: only when the owner holds it already, or
     * nobody does, or the holder's lease ran out. A new settings version (a new token) starts the poll from scratch.
     * True when {@code owner} holds the lease now.
     */
    boolean claim(UUID tenantId, String owner, int settingsVersion, Duration lease) {
        jdbc.update("insert into bot_poll_state (tenant_id, settings_version) values (?, ?) "
                + "on conflict (tenant_id) do nothing", tenantId, settingsVersion);
        return jdbc.update("""
                update bot_poll_state
                set lease_owner = ?, lease_until = now() + ? * interval '1 millisecond',
                    last_update_id = case when settings_version = ? then last_update_id else 0 end,
                    settings_version = ?
                where tenant_id = ?
                  and (lease_owner = ? or lease_owner is null or lease_until is null or lease_until < now())
                """, owner, lease.toMillis(), settingsVersion, settingsVersion, tenantId, owner) == 1;
    }

    /** The last update handled for the cooperative's bot (0 before the first). */
    long lastUpdateId(UUID tenantId) {
        List<Long> ids = jdbc.queryForList("select last_update_id from bot_poll_state where tenant_id = ?",
                Long.class, tenantId);
        return ids.isEmpty() ? 0 : ids.getFirst();
    }

    /**
     * Takes an update for {@code owner}: moves the offset past it only while {@code owner} holds the lease of this
     * settings version and nobody took the update before. True when the update is the caller's to answer, so an update
     * is answered at most once even if two instances poll for a moment.
     */
    boolean take(UUID tenantId, String owner, int settingsVersion, long updateId) {
        return jdbc.update("update bot_poll_state set last_update_id = ? where tenant_id = ? and lease_owner = ? "
                + "and settings_version = ? and last_update_id < ?", updateId, tenantId, owner, settingsVersion,
                updateId) == 1;
    }

    /** Whether {@code owner} still holds the lease of this settings version. */
    boolean holds(UUID tenantId, String owner, int settingsVersion) {
        Integer count = jdbc.queryForObject("select count(*) from bot_poll_state where tenant_id = ? "
                + "and lease_owner = ? and settings_version = ?", Integer.class, tenantId, owner, settingsVersion);
        return count != null && count > 0;
    }

    /** Gives the lease up (a clean stop), so another instance takes the bot at its next round. */
    void release(UUID tenantId, String owner) {
        jdbc.update("update bot_poll_state set lease_owner = null, lease_until = null "
                + "where tenant_id = ? and lease_owner = ?", tenantId, owner);
    }
}
