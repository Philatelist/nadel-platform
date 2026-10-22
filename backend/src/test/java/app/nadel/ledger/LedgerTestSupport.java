package app.nadel.ledger;

import app.nadel.IntegrationTest;
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
 * Fixtures for the ledger table tests: per cooperative a throw-away plot, plot meter, tariff with one version and a
 * billing month, created as the container superuser (which bypasses RLS) and removed after the test. The ledger tables
 * refuse DELETE even to the owner, so clean-up runs as {@code nadel_owner} with the maintenance setting, the same path
 * the demo data uses. The container has no {@code nadel_owner} role (migrations run as the superuser there), so this
 * class creates it, without login and with BYPASSRLS (a table owner is not filtered by RLS), and grants it the table privileges the superuser's tables would give an owner.
 */
abstract class LedgerTestSupport extends IntegrationTest {

    static final String MONTH = "2031-01-01";

    /** The rows one cooperative needs to book a charge. */
    record Side(UUID tenant, UUID plot, UUID meter, UUID chargeType, UUID month, UUID tariff, UUID version) {
    }

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> meters = new ArrayList<>();
    private final List<UUID> tariffs = new ArrayList<>();
    private final List<UUID> months = new ArrayList<>();
    private final List<UUID> tenants = new ArrayList<>();

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
    void removeLedgerFixtures() {
        for (UUID tenant : tenants) {
            asLedgerOwner(sql -> {
                sql.update("delete from meter_value_use where tenant_id = ?", tenant);
                sql.update("delete from charge_part where tenant_id = ? and charge_id in "
                        + "(select id from charge where plot_id = any(?::uuid[]))", tenant, uuidArray(plots));
                // a reversal points at its original: delete the reversals first
                sql.update("delete from charge where tenant_id = ? and plot_id = any(?::uuid[]) "
                        + "and reverses_charge_id is not null", tenant, uuidArray(plots));
                sql.update("delete from charge where tenant_id = ? and plot_id = any(?::uuid[])", tenant,
                        uuidArray(plots));
            });
        }
        JdbcTemplate owner = ownerJdbc();
        for (UUID month : months) {
            owner.update("delete from billing_month where id = ?", month);
        }
        for (UUID meter : meters) {
            owner.update("delete from meter where id = ?", meter);
        }
        for (UUID tariff : tariffs) {
            owner.update("delete from tariff_version where tariff_id = ?", tariff);
            owner.update("delete from tariff where id = ?", tariff);
        }
        for (UUID plot : plots) {
            owner.update("delete from plot where id = ?", plot);
        }
        plots.clear();
        meters.clear();
        tariffs.clear();
        months.clear();
        tenants.clear();
    }

    /** Creates the fixture rows of one cooperative. */
    Side side(UUID tenant) {
        JdbcTemplate owner = ownerJdbc();
        UUID plot = UUID.randomUUID();
        owner.update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", plot,
                tenant, "L-" + plot.toString().substring(0, 8));
        plots.add(plot);
        UUID meter = UUID.randomUUID();
        owner.update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value) "
                + "values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, date '2026-01-01', 0)", meter, tenant,
                "LEDGER-" + meter.toString().substring(0, 8), plot);
        meters.add(meter);
        UUID chargeType = owner.queryForObject(
                "select id from charge_type where tenant_id = ? and code = 'ELECTRICITY'", UUID.class, tenant);
        UUID month = UUID.randomUUID();
        owner.update("insert into billing_month (id, tenant_id, month) values (?, ?, date '" + MONTH + "')", month,
                tenant);
        months.add(month);
        UUID tariff = UUID.randomUUID();
        owner.update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, 'Ledger test')", tariff,
                tenant, chargeType);
        tariffs.add(tariff);
        UUID version = UUID.randomUUID();
        owner.update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, date '2026-01-01', 'PER_KWH', 'MONTHLY', 5.2500)", version, tenant, tariff);
        if (!tenants.contains(tenant)) {
            tenants.add(tenant);
        }
        return new Side(tenant, plot, meter, chargeType, month, tariff, version);
    }

    /** Inserts a REGULAR tariff charge of 10.00 as the superuser and returns its id. */
    UUID insertCharge(Side side, String sourceKey) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, tariff_id) values (?, ?, ?, ?, ?, "
                + "'REGULAR', 'TARIFF', 10.00, date '2031-01-01', date '2031-01-31', date '2031-01-31', ?, ?)", id,
                side.tenant(), side.plot(), side.chargeType(), side.month(), sourceKey, side.tariff());
        return id;
    }

    UUID insertPart(Side side, UUID charge) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge_part (id, tenant_id, charge_id, tariff_version_id, days, units_num, "
                + "units_den, rate) values (?, ?, ?, ?, 31, 1, 1, 5.2500)", id, side.tenant(), charge,
                side.version());
        return id;
    }

    /** Runs the work as nadel_owner with app.ledger_maintenance = 'on', in one transaction. */
    static void asLedgerOwner(Consumer<JdbcTemplate> work) {
        asNadelOwner(true, work);
    }

    /** Runs the work as nadel_owner, with or without the maintenance setting, in one transaction. */
    static void asNadelOwner(boolean maintenance, Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            // the tables exist only after the context ran the migrations, so the grant is made here, not in @BeforeAll
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            if (maintenance) {
                sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            }
            work.accept(sql);
        });
    }

    private static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }
}
