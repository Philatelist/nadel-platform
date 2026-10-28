package app.nadel.ledger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixtures for the payment table tests, on top of the ledger fixtures: payments, allocations, charge reversals and
 * adjustments, a second plot per cooperative and receipt counters, all created as the container superuser (which
 * bypasses RLS) and removed after the test. The ledger tables refuse DELETE even to the owner, so clean-up runs as
 * {@code nadel_owner} with the maintenance setting. This class's clean-up runs before the one of the superclass, which
 * removes the charges and the plots.
 */
abstract class PaymentTestSupport extends LedgerTestSupport {

    private final List<UUID> sidePlots = new ArrayList<>();
    private final List<UUID> extraPlots = new ArrayList<>();
    private final List<UUID> recalculations = new ArrayList<>();
    private final List<UUID> counterTenants = new ArrayList<>();

    @Override
    Side side(UUID tenant) {
        Side side = super.side(tenant);
        sidePlots.add(side.plot());
        return side;
    }

    @AfterEach
    void removePaymentFixtures() {
        List<UUID> plots = new ArrayList<>(sidePlots);
        plots.addAll(extraPlots);
        String plotArray = "{" + String.join(",", plots.stream().map(UUID::toString).toList()) + "}";
        String recalculationArray = "{" + String.join(",",
                recalculations.stream().map(UUID::toString).toList()) + "}";
        asLedgerOwner(sql -> {
            // releases point at their allocation, reversals at their payment: delete the pointing rows first
            sql.update("delete from allocation where plot_id = any(?::uuid[]) and releases_allocation_id is not null",
                    plotArray);
            sql.update("delete from allocation where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from payment where plot_id = any(?::uuid[]) and reverses_payment_id is not null",
                    plotArray);
            sql.update("delete from payment where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from charge where recalculation_id = any(?::uuid[])", recalculationArray);
            sql.update("delete from recalculation where id = any(?::uuid[])", recalculationArray);
        });
        for (UUID tenant : counterTenants) {
            ownerJdbc().update("delete from receipt_counter where tenant_id = ?", tenant);
        }
        for (UUID plot : extraPlots) {
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
        sidePlots.clear();
        extraPlots.clear();
        recalculations.clear();
        counterTenants.clear();
    }

    /** A second plot of the cooperative, removed after the test. */
    UUID extraPlot(UUID tenant) {
        UUID plot = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                plot, tenant, "P-" + plot.toString().substring(0, 8));
        extraPlots.add(plot);
        return plot;
    }

    /** A PAYMENT of the given source; a receipt number only for CASH. Returns its id. */
    UUID payment(Side side, UUID plot, String source, String amount, Long receipt, UUID group) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, receipt_number) values (?, ?, ?, 'PAYMENT', ?, ?::numeric, "
                + "date '2031-01-15', ?, ?, ?)", id, side.tenant(), plot, source, amount, side.month(), group, receipt);
        return id;
    }

    /** A CASH payment on the plot of the side, in its own group, with the given receipt number. */
    UUID cash(Side side, String amount, long receipt) {
        return payment(side, side.plot(), "CASH", amount, receipt, UUID.randomUUID());
    }

    /** A BANK payment on the plot of the side, in its own group. */
    UUID bank(Side side, String amount) {
        return payment(side, side.plot(), "BANK", amount, null, UUID.randomUUID());
    }

    /** A PAYMENT_REVERSAL of the whole payment (negated amount, same plot). */
    UUID reversePayment(Side side, UUID original) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, reverses_payment_id, reason) select ?, tenant_id, plot_id, "
                + "'PAYMENT_REVERSAL', source, -amount, received_on, billing_month_id, ?, id, 'Wrong payment' "
                + "from payment where id = ?", id, UUID.randomUUID(), original);
        return id;
    }

    /** A positive ENTRY allocation on the plot of the side. */
    UUID allocate(Side side, UUID payment, UUID charge, String amount) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "values (?, ?, ?, ?, ?, ?::numeric, 'ENTRY')", id, side.tenant(), payment, charge, side.plot(),
                amount);
        return id;
    }

    /** A release (negative row) of an allocation of the plot of the side. */
    UUID release(Side side, UUID allocation, UUID payment, UUID charge, String amount) {
        return release(ownerJdbc(), side, allocation, payment, charge, amount);
    }

    UUID release(JdbcTemplate sql, Side side, UUID allocation, UUID payment, UUID charge, String amount) {
        UUID id = UUID.randomUUID();
        sql.update("insert into allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, "
                + "releases_allocation_id, release_cause) values (?, ?, ?, ?, ?, ?::numeric, ?, 'CHARGE_REVERSED')", id,
                side.tenant(), payment, charge, side.plot(), amount, allocation);
        return id;
    }

    /** A REVERSAL of the whole charge. */
    UUID reverseCharge(JdbcTemplate sql, Side side, UUID charge) {
        UUID id = UUID.randomUUID();
        sql.update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) values (?, ?, ?, ?, ?, "
                + "'REVERSAL', 'MANUAL_REVERSAL', -10.00, date '2031-01-01', date '2031-01-31', date '2031-01-31', "
                + "?, 'Wrong tariff')", id, side.tenant(), side.plot(), side.chargeType(), side.month(), charge);
        return id;
    }

    /** An ADJUSTMENT of the charge (a recalculation row is created for it). */
    UUID adjustCharge(JdbcTemplate sql, Side side, UUID charge, String amount) {
        UUID recalculation = UUID.randomUUID();
        sql.update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, digest) "
                + "values (?, ?, 'MANUAL', 'Test', 0, 0, 0, 'x')", recalculation, side.tenant());
        recalculations.add(recalculation);
        UUID id = UUID.randomUUID();
        sql.update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, adjusts_charge_id, reason, recalculation_id) "
                + "values (?, ?, ?, ?, ?, 'ADJUSTMENT', 'RECALCULATION', ?::numeric, date '2031-01-01', "
                + "date '2031-01-31', date '2031-01-31', ?, 'Tariff fixed', ?)", id, side.tenant(), side.plot(),
                side.chargeType(), side.month(), amount, charge, recalculation);
        return id;
    }

    /** Inserts the receipt counter row of the cooperative (removed after the test). */
    void counter(UUID tenant, long last) {
        ownerJdbc().update("insert into receipt_counter (tenant_id, last_number) values (?, ?)", tenant, last);
        counterTenants.add(tenant);
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
