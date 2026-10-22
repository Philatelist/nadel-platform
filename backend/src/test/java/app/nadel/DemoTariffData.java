package app.nadel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The demo tariffs of {@code R__demo_data.sql} (membership, land tax, electricity and the "Road repair" fee of the demo
 * cooperative) for tests that need a clean tariffs state: they {@link #clear} everything before and after the test and
 * {@link #restore} the demo rows after it, so the tests that run later still find them. The rows are restored by running
 * the same SQL block the demo migration runs (between the {@code BEGIN demo tariffs} and {@code END demo tariffs}
 * comments), so the two cannot drift apart.
 */
public final class DemoTariffData {

    private static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DemoTariffData() {
    }

    /**
     * Leaves the two cooperatives with exactly the five seeded charge types and no tariffs: removes every scope row,
     * version, tariff and charge type (the owner role may, the application role may not) and seeds the types again.
     */
    public static void clear(JdbcTemplate owner) {
        owner.update("delete from tariff_scope_plot where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from tariff_version where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from tariff where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from charge_type where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.queryForList("select seed_charge_types(?)", DEMO_TENANT);
        owner.queryForList("select seed_charge_types(?)", OTHER_TENANT);
    }

    /** Puts the demo tariffs back after {@link #clear}; idempotent. */
    public static void restore(JdbcTemplate owner) {
        owner.execute(block());
    }

    private static String block() {
        try (InputStream in = DemoTariffData.class.getResourceAsStream("/db/demo/R__demo_data.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = sql.indexOf("-- BEGIN demo tariffs");
            int end = sql.indexOf("-- END demo tariffs");
            if (begin < 0 || end < begin) {
                throw new IllegalStateException("R__demo_data.sql has no demo tariffs block");
            }
            return sql.substring(begin, end);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the demo data", e);
        }
    }
}
