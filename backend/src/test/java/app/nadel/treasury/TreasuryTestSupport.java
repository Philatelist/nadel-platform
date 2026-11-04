package app.nadel.treasury;

import static org.awaitility.Awaitility.await;

import app.nadel.IntegrationTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixtures for the treasury table tests: two throw-away cooperatives per test ({@link #tenantA}, {@link #tenantB}) with
 * the seeded roles, accounts and categories, and rows created as the container superuser (which bypasses RLS). The
 * append-only tables refuse DELETE even to the owner, so clean-up runs as {@code nadel_owner} with the maintenance
 * setting, the path the demo data uses. The container has no {@code nadel_owner} role, so this class creates it as the
 * ledger tests do.
 */
abstract class TreasuryTestSupport extends IntegrationTest {

    UUID tenantA;
    UUID tenantB;

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

    @BeforeEach
    void createCooperatives() {
        tenantA = cooperative();
        tenantB = cooperative();
    }

    @AfterEach
    void removeCooperatives() {
        for (UUID tenant : new UUID[] {tenantA, tenantB}) {
            removeCooperative(tenant);
        }
    }

    /** A cooperative with the seeded roles, charge types, accounts and categories. */
    UUID cooperative() {
        UUID tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, 'Treasury test cooperative')", tenant);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        return tenant;
    }

    void removeCooperative(UUID tenant) {
        // the asynchronous listeners (audit, plot status) of what a test entered through the API finish first
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the cleanup goes on
        }
        asNadelOwner(true, sql -> {
            sql.update("delete from bank_difference where tenant_id = ?", tenant);
            sql.update("delete from bank_reconciliation where tenant_id = ?", tenant);
            sql.update("delete from cash_count where tenant_id = ?", tenant);
            sql.update("delete from expense_attachment where tenant_id = ?", tenant);
            for (String table : new String[] {"expense", "other_income", "handover"}) {
                sql.update("delete from " + table + " where tenant_id = ? and reason is not null", tenant);
                sql.update("delete from " + table + " where tenant_id = ?", tenant);
            }
            sql.update("delete from account_movement where tenant_id = ? and reverses_movement_id is not null", tenant);
            sql.update("delete from account_movement where tenant_id = ?", tenant);
            sql.update("delete from allocation where tenant_id = ? and releases_allocation_id is not null", tenant);
            sql.update("delete from allocation where tenant_id = ?", tenant);
            sql.update("delete from payment where tenant_id = ? and reverses_payment_id is not null", tenant);
            sql.update("delete from payment where tenant_id = ?", tenant);
            sql.update("delete from receipt_counter where tenant_id = ?", tenant);
            sql.update("delete from billing_month where tenant_id = ?", tenant);
            sql.update("delete from money_account where tenant_id = ?", tenant);
            sql.update("delete from expense_category where tenant_id = ?", tenant);
        });
        for (String table : new String[] {"audit_entry", "plot_status", "ownership", "member_role", "role_permission",
                "role", "charge_type", "plot", "member", "cooperative_settings"}) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", tenant);
        }
        deleteDataKeys(tenant);
        ownerJdbc().update("delete from tenant where id = ?", tenant);
    }

    // ---- fixtures (superuser)

    /** The seeded account id (md5 of tenant and label). */
    UUID account(UUID tenant, String kind) {
        return ownerJdbc().queryForObject("select id from money_account where tenant_id = ? and kind = ?", UUID.class,
                tenant, kind);
    }

    UUID category(UUID tenant, String kind, String systemCode) {
        return ownerJdbc().queryForObject(
                "select id from expense_category where tenant_id = ? and kind = ? and system_code = ?", UUID.class,
                tenant, kind, systemCode);
    }

    UUID member(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'Treasury')",
                id, tenant);
        return id;
    }

    UUID plot(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                tenant, "T-" + id.toString().substring(0, 8));
        return id;
    }

    UUID billingMonth(UUID tenant, String firstDay) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into billing_month (id, tenant_id, month) values (?, ?, date '" + firstDay + "')", id,
                tenant);
        return id;
    }

    /** A journal movement as the superuser; kind MEMBER_PAYMENT needs a payment row, use {@link #payment}. */
    UUID movement(UUID tenant, UUID account, String kind, String amount, String movedOn, String bookingMonth) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into account_movement (id, tenant_id, account_id, kind, amount, moved_on, "
                + "booking_month, source_type, source_id) values (?, ?, ?, ?, ?, date '" + movedOn + "', date '"
                + bookingMonth + "', 'EXPENSE', ?)", id, tenant, account, kind, new BigDecimal(amount),
                UUID.randomUUID());
        return id;
    }

    /** The reversal of a movement (negated amount, same account and kind) with a reason. */
    UUID reversalOf(UUID tenant, UUID original) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into account_movement (id, tenant_id, account_id, kind, amount, moved_on, "
                + "booking_month, source_type, source_id, reverses_movement_id, reason) select ?, tenant_id, account_id, "
                + "kind, -amount, moved_on, booking_month, source_type, source_id, id, 'Entered twice' "
                + "from account_movement where tenant_id = ? and id = ?", id, tenant, original);
        return id;
    }

    /** An expense on the cash account of the tenant with its own movement. */
    UUID expense(UUID tenant) {
        UUID movement = movement(tenant, account(tenant, "CASH"), "EXPENSE", "-10.00", "2033-01-10", "2033-01-01");
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into expense (id, tenant_id, account_id, category_id, amount, expense_date, "
                + "movement_id) values (?, ?, ?, ?, 10.00, date '2033-01-10', ?)", id, tenant,
                account(tenant, "CASH"), category(tenant, "EXPENSE", "WATER"), movement);
        return id;
    }

    UUID attachment(UUID tenant, UUID expense, int position) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into expense_attachment (id, tenant_id, expense_id, position, content_type, "
                + "file_name, size_bytes, sha256, content) values (?, ?, ?, ?, 'application/pdf', 'receipt.pdf', 4, "
                + "decode(repeat('ab', 32), 'hex'), '\\x25504446'::bytea)", id, tenant, expense, position);
        return id;
    }

    UUID otherIncome(UUID tenant) {
        UUID movement = movement(tenant, account(tenant, "BANK"), "OTHER_INCOME", "5.00", "2033-01-11", "2033-01-01");
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into other_income (id, tenant_id, account_id, category_id, amount, received_on, "
                + "movement_id) values (?, ?, ?, ?, 5.00, date '2033-01-11', ?)", id, tenant,
                account(tenant, "BANK"), category(tenant, "INCOME", "BANK_INTEREST"), movement);
        return id;
    }

    UUID handover(UUID tenant) {
        UUID out = movement(tenant, account(tenant, "CASH"), "HANDOVER_OUT", "-20.00", "2033-01-12", "2033-01-01");
        UUID in = movement(tenant, account(tenant, "BANK"), "HANDOVER_IN", "20.00", "2033-01-12", "2033-01-01");
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into handover (id, tenant_id, amount, handed_on, document_number, out_movement_id, "
                + "in_movement_id) values (?, ?, 20.00, date '2033-01-12', 'PKO-1', ?, ?)", id, tenant, out, in);
        return id;
    }

    /** A cash count with a shortage of 5.00 (not booked). */
    UUID cashCount(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into cash_count (id, tenant_id, account_id, counted_at, counted_on, counted_amount, "
                + "book_balance, difference, explanation) values (?, ?, ?, now(), current_date, 95.00, 100.00, -5.00, "
                + "'Counted short')", id, tenant, account(tenant, "CASH"));
        return id;
    }

    UUID bankReconciliation(UUID tenant, String month) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into bank_reconciliation (id, tenant_id, month, statement_opening, "
                + "statement_incoming, statement_outgoing, statement_closing) values (?, ?, date '" + month
                + "', 100.00, 50.00, 20.00, 130.00)", id, tenant);
        return id;
    }

    /** A bank difference of −7.00 booked on 30.06.2033 with its reconciliation of June 2033 and its reason. */
    UUID bankDifference(UUID tenant) {
        UUID reconciliation = bankReconciliation(tenant, "2033-06-01");
        UUID movement = movement(tenant, account(tenant, "BANK"), "BANK_DIFFERENCE", "-7.00", "2033-06-30",
                "2033-06-01");
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into bank_difference (id, tenant_id, reconciliation_id, movement_id, reason) "
                + "values (?, ?, ?, ?, 'Bank fee')", id, tenant, reconciliation, movement);
        return id;
    }

    // ---- helpers

    /** An int-valued statement as the cooperative (keeps assertThat unambiguous). */
    int intIn(UUID tenant, java.util.function.Function<JdbcTemplate, Integer> work) {
        return inTenant(tenant, work);
    }

    /** Runs the work as nadel_owner, with or without the maintenance setting, in one transaction. */
    static void asNadelOwner(boolean maintenance, Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            // the tables exist only after the context ran the migrations, so the grant is made here
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            if (maintenance) {
                sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            }
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
