package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one writer of releases (feature 015 §2.3, technical considerations 2.4) against the database: a charge path that
 * reversed a paid charge in its transaction calls {@link AllocationReleaser#settleCharges} under the plot ledger lock
 * and commits with the money released ({@code CHARGE_REVERSED}, by the system); one that forgets it is refused at
 * commit by the deferred backstop; a call without the plot ledger lock fails before it writes anything.
 */
class AllocationReleaserTests extends IntegrationTest {

    @Autowired
    AllocationReleaser releaser;

    @Autowired
    PlotLedgerLock plotLocks;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactions;

    private UUID plot;
    private UUID charge;
    private UUID payment;
    private boolean monthCreated;

    /** A plot with membership 100.00 paid in full by a bank payment of 100.00, written as the superuser. */
    @BeforeEach
    void paidCharge() {
        JdbcTemplate owner = ownerJdbc();
        plot = UUID.randomUUID();
        owner.update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", plot,
                DEMO_TENANT, "AR" + plot.toString().substring(0, 8));
        monthCreated = owner.update("insert into billing_month (tenant_id, month) values (?, date '2031-03-01') "
                + "on conflict (tenant_id, month) do nothing", DEMO_TENANT) == 1;
        UUID month = owner.queryForObject("select id from billing_month where tenant_id = ? "
                + "and month = date '2031-03-01'", UUID.class, DEMO_TENANT);
        charge = UUID.randomUUID();
        owner.update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) select ?, ?, ?, id, ?, 'REGULAR', "
                + "'TARIFF', 100.00, date '2031-03-01', date '2031-03-31', date '2031-03-01', ? from charge_type "
                + "where tenant_id = ? and code = 'MEMBERSHIP'", charge, DEMO_TENANT, plot, month, "TEST:" + charge,
                DEMO_TENANT);
        payment = UUID.randomUUID();
        owner.update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) values (?, ?, ?, 'PAYMENT', 'BANK', 100.00, date '2031-03-05', ?, ?)",
                payment, DEMO_TENANT, plot, month, payment);
        owner.update("insert into allocation (tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "values (?, ?, ?, ?, 100.00, 'ENTRY')", DEMO_TENANT, payment, charge, plot);
    }

    @AfterEach
    void removeRows() {
        JdbcTemplate sql = ownerJdbc();
        sql.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        sql.execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            // the ledger tables are append-only: nadel_owner with the maintenance setting may clean up
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            sql.update("delete from allocation where plot_id = ? and releases_allocation_id is not null", plot);
            sql.update("delete from allocation where plot_id = ?", plot);
            sql.update("delete from payment where plot_id = ?", plot);
            sql.update("delete from charge where plot_id = ? and reverses_charge_id is not null", plot);
            sql.update("delete from charge where plot_id = ?", plot);
            sql.update("delete from plot where id = ?", plot);
            if (monthCreated) {
                sql.update("delete from billing_month where tenant_id = ? and month = date '2031-03-01'",
                        DEMO_TENANT);
            }
        });
    }

    /** Inserts the reversal of the charge in the caller's transaction, as the application role. */
    private void reverseCharge() {
        entityManager.createNativeQuery("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, "
                + "kind, source, amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select "
                + "gen_random_uuid(), tenant_id, plot_id, charge_type_id, billing_month_id, 'REVERSAL', "
                + "'MANUAL_REVERSAL', -amount, service_from, service_to, accrued_on, id, 'Test' from charge "
                + "where id = :charge").setParameter("charge", charge).executeUpdate();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return TenantContext.runAs(DEMO_TENANT, () -> new TransactionTemplate(transactions)
                .execute(status -> work.get()));
    }

    @Test
    void aReversedPaidChargeIsSettledByTheSystemInTheSameTransaction() {
        List<Release> releases = inTransaction(() -> {
            plotLocks.lockAll(DEMO_TENANT, Set.of(plot));
            reverseCharge();
            return releaser.settleCharges(DEMO_TENANT, Set.of(plot), null);
        });

        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).cause()).isEqualTo(ReleaseCause.CHARGE_REVERSED);
        assertThat(releases.get(0).amount().toString()).isEqualTo("100.00");
        Map<String, Object> row = ownerJdbc().queryForMap("select amount, release_cause, created_by, payment_id "
                + "from allocation where plot_id = ? and amount < 0", plot);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-100.00");
        assertThat(row).containsEntry("release_cause", "CHARGE_REVERSED").containsEntry("payment_id", payment);
        assertThat(row.get("created_by")).isNull();
        // nothing left to settle: a second call writes nothing
        assertThat(inTransaction(() -> {
            plotLocks.lockAll(DEMO_TENANT, Set.of(plot));
            return releaser.settleCharges(DEMO_TENANT, Set.of(plot), null);
        })).isEmpty();
    }

    @Test
    void aChargePathThatForgetsTheReleaserIsRefusedAtCommit() {
        assertThatThrownBy(() -> inTransaction(() -> {
            reverseCharge();
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("allocation_charge_cap");
        assertThat(ownerJdbc().queryForObject("select count(*) from charge where plot_id = ?", Integer.class, plot))
                .isOne();
    }

    @Test
    void theReleaserNeedsThePlotLedgerLock() {
        assertThatThrownBy(() -> inTransaction(() -> releaser.settleCharges(DEMO_TENANT, Set.of(plot), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inTransaction(() -> releaser.releasePayment(DEMO_TENANT, plot, payment,
                ReleaseCause.PAYMENT_REVERSED, null))).isInstanceOf(IllegalStateException.class);
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where plot_id = ?", Integer.class,
                plot)).isOne();
    }
}
