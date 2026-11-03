package app.nadel.notifications.internal;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixtures for the tests of the delivery tables (V29): rows created as the container superuser (which bypasses RLS)
 * and removed after the test. The append-only tables refuse DELETE even to the owner, so clean-up runs as
 * {@code nadel_owner} with the maintenance setting, the path the demo data uses.
 */
abstract class NotificationsTestSupport extends IntegrationTest {

    static final List<String> TABLES = List.of("delivery_settings", "telegram_link", "telegram_link_code",
            "announcement", "delivery", "delivery_lane", "bot_poll_state");

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> tenantsWithMonth = new ArrayList<>();

    @BeforeAll
    static void createOwnerRole() {
        ownerJdbc().execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        ownerJdbc().execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
    }

    @AfterEach
    void removeNotificationFixtures() {
        String plotArray = "{" + String.join(",", plots.stream().map(UUID::toString).toList()) + "}";
        asNadelOwner(sql -> {
            for (String table : List.of("delivery", "announcement", "telegram_link", "telegram_link_code",
                    "delivery_settings", "delivery_lane", "bot_poll_state")) {
                sql.update("delete from " + table);
            }
            sql.update("delete from payment where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from invoice where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from billing_month where month = date '2031-03-01'");
        });
        for (UUID plot : plots) {
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
        plots.clear();
        tenantsWithMonth.clear();
    }

    UUID plot(UUID tenant) {
        UUID plot = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                plot, tenant, "N-" + plot.toString().substring(0, 8));
        plots.add(plot);
        return plot;
    }

    UUID invoice(UUID tenant, UUID plot, int version) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into invoice (id, tenant_id, plot_id, month, number, version, snapshot, "
                + "content_hash, due, as_of) values (?, ?, ?, date '2031-03-01', ?, ?, '{}'::jsonb, '\\x01'::bytea, "
                + "12.50, now())", id, tenant, plot, "2031-03/" + id.toString().substring(0, 6), version);
        return id;
    }

    /** A bank payment of March 2031 for the plot. */
    UUID payment(UUID tenant, UUID plot) {
        if (!tenantsWithMonth.contains(tenant)) {
            ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, date '2031-03-01') "
                    + "on conflict do nothing", tenant);
            tenantsWithMonth.add(tenant);
        }
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) select ?, ?, ?, 'PAYMENT', 'BANK', 5.00, date '2031-03-10', bm.id, ? "
                + "from billing_month bm where bm.tenant_id = ? and bm.month = date '2031-03-01'",
                id, tenant, plot, UUID.randomUUID(), tenant);
        return id;
    }

    UUID announcement(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into announcement (id, tenant_id, text, channels) values (?, ?, 'Meeting on Sunday', "
                + "array['EMAIL','TELEGRAM'])", id, tenant);
        return id;
    }

    UUID delivery(UUID tenant, UUID invoice, UUID member, String channel) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into delivery (id, tenant_id, kind, invoice_id, invoice_version, billing_month, "
                + "member_id, channel, status) values (?, ?, 'INVOICE', ?, 1, date '2031-03-01', ?, ?, 'QUEUED')",
                id, tenant, invoice, member, channel);
        return id;
    }

    UUID link(UUID tenant, UUID member, int chat) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into telegram_link (id, tenant_id, member_id, chat_id_enc, chat_id_bidx) "
                + "values (?, ?, ?, ?, ?)", id, tenant, member, new byte[] {1, (byte) chat}, new byte[] {2, (byte) chat});
        return id;
    }

    void linkCode(UUID tenant, UUID member, int code) {
        ownerJdbc().update("insert into telegram_link_code (tenant_id, code_hash, member_id, purpose, expires_at) "
                + "values (?, ?, ?, 'ACCOUNT', now() + interval '1 day')", tenant, new byte[] {3, (byte) code}, member);
    }

    void settings(UUID tenant, boolean withBotToken) {
        if (withBotToken) {
            ownerJdbc().update("insert into delivery_settings (tenant_id, bot_token_enc, bot_token_last4, bot_username) "
                    + "values (?, '\\x0102'::bytea, '1234', 'demo_bot')", tenant);
        } else {
            ownerJdbc().update("insert into delivery_settings (tenant_id) values (?)", tenant);
        }
    }

    /** Runs the work as nadel_owner with the maintenance setting, in one transaction. */
    static void asNadelOwner(Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(sql);
        });
    }

    static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
