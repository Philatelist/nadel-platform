package app.nadel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The demo meters of {@code R__demo_data.sql} (the electricity tree, plot meters, readings and the permissions of the
 * Electrician role) for tests that need a clean meters state: they {@link #clear} the meter tables before and after the
 * test and {@link #restore} the demo rows after it, so the tests that run later still find them. The rows are restored
 * by running the same SQL block the demo migration runs (between the {@code BEGIN demo meters} and {@code END demo
 * meters} comments), so the two cannot drift apart.
 */
public final class DemoMeterData {

    private static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DemoMeterData() {
    }

    /**
     * Removes every reading, tree period and meter of the two cooperatives (the owner role may, the application role
     * may not), after the ledger rows that refer to meters ({@link DemoLedgerData#clear}: charges, runs, value uses,
     * water connections).
     */
    public static void clear(JdbcTemplate owner) {
        DemoLedgerData.clear(owner);
        owner.update("delete from meter_reading where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from meter_parent where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from meter where tenant_id in (?, ?) and replaces_meter_id is not null", DEMO_TENANT,
                OTHER_TENANT);
        owner.update("delete from meter where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
    }

    /** Puts the demo meters back after {@link #clear}; idempotent. */
    public static void restore(JdbcTemplate owner) {
        owner.execute(block());
    }

    private static String block() {
        try (InputStream in = DemoMeterData.class.getResourceAsStream("/db/demo/R__demo_data.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = sql.indexOf("-- BEGIN demo meters");
            int end = sql.indexOf("-- END demo meters");
            if (begin < 0 || end < begin) {
                throw new IllegalStateException("R__demo_data.sql has no demo meters block");
            }
            return sql.substring(begin, end);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the demo data", e);
        }
    }
}
