package app.nadel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo ledger of {@code R__demo_data.sql} (the block between {@code BEGIN demo ledger} and {@code END demo ledger}:
 * months, charges, runs, the "not charged" items, water connections, the settings of the ledger and the plot debts of
 * the demo cooperative). Integration tests start without it (the Flyway placeholder {@code demoledger} is {@code off}
 * in {@link IntegrationTest}), so the demo cooperative they share has no charges; a test that wants the demo ledger
 * calls {@link #restore} and removes it again with {@link #clear}. The rows are restored by running the same SQL block
 * the demo migration runs, so the two cannot drift apart.
 * <p>
 * The ledger tables refuse {@code UPDATE} and {@code DELETE} (triggers, ADR 0006) except to {@code nadel_owner} with
 * {@code app.ledger_maintenance = 'on'}, so both methods run as that role in one transaction. The test container has no
 * {@code nadel_owner} (migrations run as the superuser there); it is created here when missing, without login and with
 * BYPASSRLS, and given the table privileges an owner would have.
 */
public final class DemoLedgerData {

    private static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** The meter the block gives plot 25 (installed before the first electricity tariff version). */
    private static final String PLOT_25_METER =
            "md5('demo-meter:11111111-1111-1111-1111-111111111111:25:ELECTRICITY')::uuid";

    /** The "Спільні споживачі" tariff the block creates and its version. */
    private static final String COMMON_CONSUMERS_TARIFF = "ffffffff-0000-0000-0000-000000000005";
    private static final String COMMON_CONSUMERS_VERSION = "ffffffff-0000-0000-0001-000000000005";

    private DemoLedgerData() {
    }

    /**
     * Removes every ledger row of the two cooperatives (charges with their parts and value uses, runs, "not charged"
     * items, billing months), their water connections and common consumer settings, and what only the demo ledger
     * block adds (plot 25's meter, the "Спільні споживачі" tariff); puts the demo cooperative's ledger settings back
     * to their defaults and its plots back to no debt (GREEN with a current owner, otherwise GREY). Safe to call when
     * nothing is loaded. {@link DemoMeterData#clear} and {@link DemoTariffData#clear} call it first, since ledger
     * rows refer to meters and tariffs.
     */
    public static void clear(JdbcTemplate owner) {
        asLedgerOwner(owner, sql -> {
            for (String table : new String[] {"meter_value_use", "charge_part"}) {
                sql.update("delete from " + table + " where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
            }
            sql.update("delete from recalculation_line where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
            sql.update("delete from charge where tenant_id in (?, ?) and (reverses_charge_id is not null "
                    + "or adjusts_charge_id is not null)", DEMO_TENANT, OTHER_TENANT);
            for (String table : new String[] {"charge", "losses_run_node", "losses_run", "common_consumer_run_line",
                    "common_consumer_run", "not_charged_item", "recalculation", "billing_month", "common_consumer_billing",
                    "common_consumer_user", "plot_water_connection"}) {
                sql.update("delete from " + table + " where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
            }
            sql.update("delete from meter_reading where meter_id = " + PLOT_25_METER);
            sql.update("delete from meter_parent where meter_id = " + PLOT_25_METER);
            sql.update("delete from meter where id = " + PLOT_25_METER);
            sql.update("delete from tariff_version where id = ?::uuid", COMMON_CONSUMERS_VERSION);
            sql.update("delete from tariff where id = ?::uuid", COMMON_CONSUMERS_TARIFF);
            sql.update("update cooperative_settings set losses_mode = 'FIXED_PERCENT', losses_percent = 0, "
                    + "common_consumer_default_split = 'CONNECTED_PLOTS', red_amount = 1000.00, red_days = 90 "
                    + "where tenant_id = ?", DEMO_TENANT);
            // a row with nothing but defaults means what no row means; the demo script itself writes none
            sql.update("delete from cooperative_settings where tenant_id = ? "
                    + "and member_plot_status_visibility = 'OWN_ONLY'", DEMO_TENANT);
            sql.update("update plot_status s set debt = 0, oldest_debt_date = null, updated_at = now(), "
                    + "status = case when p.kind = 'PLOT' and exists (select 1 from ownership o "
                    + "where o.plot_id = p.id and o.valid_to is null) then 'GREEN' else 'GREY' end "
                    + "from plot p where p.id = s.plot_id and p.tenant_id = ?", DEMO_TENANT);
        });
    }

    /**
     * Loads the demo ledger: runs the block with the placeholder switched on. The demo meters and tariffs must be there
     * (they are unless a test cleared them without restoring); the block first removes what an earlier load booked, so
     * it is idempotent.
     */
    public static void restore(JdbcTemplate owner) {
        String block = block().replace("${demoledger}", "on");
        asLedgerOwner(owner, sql -> sql.execute(block));
    }

    /**
     * Runs the work as {@code nadel_owner} with {@code app.ledger_maintenance = 'on'}, in one transaction; creates the
     * role first when the container has none.
     */
    public static void asLedgerOwner(JdbcTemplate owner, Consumer<JdbcTemplate> work) {
        owner.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        tx.executeWithoutResult(status -> {
            owner.execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
            owner.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            owner.execute("SET LOCAL ROLE nadel_owner");
            owner.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(owner);
        });
    }

    private static String block() {
        try (InputStream in = DemoLedgerData.class.getResourceAsStream("/db/demo/R__demo_data.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = sql.indexOf("-- BEGIN demo ledger");
            int end = sql.indexOf("-- END demo ledger");
            if (begin < 0 || end < begin) {
                throw new IllegalStateException("R__demo_data.sql has no demo ledger block");
            }
            return sql.substring(begin, end);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the demo data", e);
        }
    }
}
