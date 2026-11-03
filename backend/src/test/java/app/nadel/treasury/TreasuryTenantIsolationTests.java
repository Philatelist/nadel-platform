package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ten treasury tables (nine of V32 and {@code bank_difference} of V34): cooperatives are isolated (RLS with USING and WITH CHECK, nothing visible without a
 * cooperative), every table has the policy and a tenant index, and links never cross cooperatives (composite foreign
 * keys).
 */
class TreasuryTenantIsolationTests extends TreasuryTestSupport {

    private static final List<String> TABLES = List.of("money_account", "account_movement", "handover",
            "expense_category", "expense", "expense_attachment", "other_income", "cash_count", "bank_reconciliation",
            "bank_difference");

    private UUID expenseB;
    private UUID movementB;

    @BeforeEach
    void createFixtures() {
        for (UUID tenant : List.of(tenantA, tenantB)) {
            UUID expense = expense(tenant);
            attachment(tenant, expense, 1);
            otherIncome(tenant);
            handover(tenant);
            cashCount(tenant);
            bankReconciliation(tenant, "2033-01-01");
            bankDifference(tenant);
        }
        expenseB = ownerJdbc().queryForObject("select id from expense where tenant_id = ?", UUID.class, tenantB);
        movementB = ownerJdbc().queryForObject("select movement_id from expense where tenant_id = ?", UUID.class,
                tenantB);
    }

    @Test
    void everyTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation' "
                            + "and qual like '%current_tenant_id%' and with_check like '%current_tenant_id%'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
            assertThat(ownerJdbc().queryForObject("select count(*) from pg_constraint where conrelid = "
                    + "('public.' || ?)::regclass and contype = 'u' and pg_get_constraintdef(oid) like "
                    + "'UNIQUE (tenant_id, id)'", Integer.class, table)).as("unique (tenant_id, id) on %s", table)
                    .isEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRows() {
        for (UUID tenant : List.of(tenantA, tenantB)) {
            for (String table : TABLES) {
                assertThat(count(tenant, table, "where tenant_id = '" + tenant + "'")).as(table).isPositive();
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).as(table).isZero();
                assertThat(count(tenant, table, "")).as(table).isEqualTo(
                        ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?",
                                Integer.class, tenant));
            }
        }
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID nobody = UUID.randomUUID();

        for (String table : TABLES) {
            assertThat(count(nobody, table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        UUID cashB = account(tenantB, "CASH");
        UUID bankB = account(tenantB, "BANK");
        UUID waterB = category(tenantB, "EXPENSE", "WATER");
        List<String> inserts = List.of(
                "insert into money_account (tenant_id, kind) values ('" + tenantB + "', 'CASH')",
                "insert into account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, "
                        + "source_type, source_id) values ('" + tenantB + "', '" + cashB + "', 'EXPENSE', -1.00, "
                        + "date '2033-01-01', date '2033-01-01', 'EXPENSE', gen_random_uuid())",
                "insert into handover (tenant_id, amount, handed_on, document_number, out_movement_id, "
                        + "in_movement_id) values ('" + tenantB + "', 1.00, date '2033-01-01', 'X', '" + movementB
                        + "', gen_random_uuid())",
                "insert into expense_category (tenant_id, kind, name) values ('" + tenantB + "', 'EXPENSE', 'Own')",
                "insert into expense (tenant_id, account_id, category_id, amount, expense_date, movement_id) values ('"
                        + tenantB + "', '" + cashB + "', '" + waterB + "', 1.00, date '2033-01-01', '" + movementB + "')",
                "insert into expense_attachment (tenant_id, expense_id, position, content_type, file_name, size_bytes, "
                        + "sha256, content) values ('" + tenantB + "', '" + expenseB + "', 2, 'application/pdf', "
                        + "'a.pdf', 4, decode(repeat('ab', 32), 'hex'), '\\x25504446'::bytea)",
                "insert into other_income (tenant_id, account_id, category_id, amount, received_on, movement_id) "
                        + "values ('" + tenantB + "', '" + bankB + "', '" + waterB + "', 1.00, date '2033-01-01', '"
                        + movementB + "')",
                "insert into cash_count (tenant_id, account_id, counted_at, counted_on, counted_amount, book_balance, "
                        + "difference) values ('" + tenantB + "', '" + cashB + "', now(), current_date, 1.00, 1.00, 0)",
                "insert into bank_reconciliation (tenant_id, month, statement_opening, statement_incoming, "
                        + "statement_outgoing, statement_closing) values ('" + tenantB + "', date '2033-02-01', 0, 0, "
                        + "0, 0)",
                "insert into bank_difference (tenant_id, reconciliation_id, movement_id, reason) values ('" + tenantB
                        + "', '" + reconciliationB() + "', '" + movementB + "', 'Fee')");
        assertThat(inserts).hasSize(TABLES.size());

        for (String insert : inserts) {
            assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(insert)))
                    .as(insert)
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("violates row-level security policy");
        }
    }

    @Test
    void cannotUpdateTheNarrowColumnsOfAnotherCooperative() {
        assertThat(intIn(tenantA, sql -> sql.update("update expense_category set archived = true "
                + "where tenant_id = ?", tenantB))).isZero();
        assertThat(intIn(tenantA, sql -> sql.update("update money_account set opening_amount = 1.00, "
                + "opening_date = current_date, opening_set_at = now() where tenant_id = ?", tenantB))).isZero();
        assertThat(intIn(tenantA, sql -> sql.update("update bank_reconciliation set status = 'RECONCILED' "
                + "where tenant_id = ?", tenantB))).isZero();
        assertThat(intIn(tenantA, sql -> sql.update("update cash_count set booked_movement_id = ? "
                + "where tenant_id = ?", movementB, tenantB))).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from expense_category where tenant_id = ? and archived",
                Integer.class, tenantB)).isZero();
    }

    // ---- composite foreign keys

    @Test
    void aMovementCannotLinkRowsOfAnotherCooperative() {
        UUID paymentRow = foreignPayment();

        assertForeignKey("account_movement_account_fk", "insert into account_movement (tenant_id, account_id, kind, "
                + "amount, moved_on, booking_month, source_type, source_id) values ('" + tenantA + "', '"
                + account(tenantB, "CASH") + "', 'EXPENSE', -1.00, date '2033-01-01', date '2033-01-01', 'EXPENSE', "
                + "gen_random_uuid())");
        assertForeignKey("account_movement_reverses_fk", "insert into account_movement (tenant_id, account_id, kind, "
                + "amount, moved_on, booking_month, source_type, source_id, reverses_movement_id, reason) values ('"
                + tenantA + "', '" + account(tenantA, "CASH") + "', 'EXPENSE', 10.00, date '2033-01-01', "
                + "date '2033-01-01', 'EXPENSE', gen_random_uuid(), '" + movementB + "', 'Wrong')");
        assertForeignKey("account_movement_payment_fk", "insert into account_movement (tenant_id, account_id, kind, "
                + "amount, moved_on, booking_month, source_type, source_id, payment_row_id) values ('" + tenantA + "', '"
                + account(tenantA, "CASH") + "', 'MEMBER_PAYMENT', 1.00, date '2033-01-01', date '2033-01-01', "
                + "'PAYMENT', '" + paymentRow + "', '" + paymentRow + "')");
    }

    @Test
    void anExpenseCannotLinkRowsOfAnotherCooperative() {
        UUID ownMovement = movement(tenantA, account(tenantA, "CASH"), "EXPENSE", "-3.00", "2033-01-20", "2033-01-01");
        String head = "insert into expense (tenant_id, account_id, category_id, amount, expense_date, movement_id) "
                + "values ('" + tenantA + "', ";

        assertForeignKey("expense_account_fk", head + "'" + account(tenantB, "CASH") + "', '"
                + category(tenantA, "EXPENSE", "WATER") + "', 1.00, date '2033-01-20', '" + ownMovement + "')");
        assertForeignKey("expense_category_fk", head + "'" + account(tenantA, "CASH") + "', '"
                + category(tenantB, "EXPENSE", "WATER") + "', 1.00, date '2033-01-20', '" + ownMovement + "')");
        assertForeignKey("expense_movement_fk", head + "'" + account(tenantA, "CASH") + "', '"
                + category(tenantA, "EXPENSE", "WATER") + "', 1.00, date '2033-01-20', '" + movementB + "')");
        assertForeignKey("expense_attachment_expense_fk", "insert into expense_attachment (tenant_id, expense_id, "
                + "position, content_type, file_name, size_bytes, sha256, content) values ('" + tenantA + "', '"
                + expenseB + "', 3, 'application/pdf', 'a.pdf', 4, decode(repeat('ab', 32), 'hex'), "
                + "'\\x25504446'::bytea)");
    }

    @Test
    void incomeHandoverCountAndReconciliationCannotLinkRowsOfAnotherCooperative() {
        UUID ownMovement = movement(tenantA, account(tenantA, "BANK"), "OTHER_INCOME", "3.00", "2033-01-20",
                "2033-01-01");

        assertForeignKey("other_income_category_fk", "insert into other_income (tenant_id, account_id, category_id, "
                + "amount, received_on, movement_id) values ('" + tenantA + "', '" + account(tenantA, "BANK") + "', '"
                + category(tenantB, "INCOME", "GRANTS") + "', 1.00, date '2033-01-20', '" + ownMovement + "')");
        assertForeignKey("handover_out_movement_fk", "insert into handover (tenant_id, amount, handed_on, "
                + "document_number, out_movement_id, in_movement_id) values ('" + tenantA + "', 1.00, "
                + "date '2033-01-20', 'X', '" + movementB + "', '" + ownMovement + "')");
        assertForeignKey("cash_count_account_fk", "insert into cash_count (tenant_id, account_id, counted_at, "
                + "counted_on, counted_amount, book_balance, difference) values ('" + tenantA + "', '"
                + account(tenantB, "CASH") + "', now(), current_date, 1.00, 1.00, 0)");
        assertForeignKey("bank_reconciliation_reopened_movement_fk", "insert into bank_reconciliation (tenant_id, "
                + "month, statement_opening, statement_incoming, statement_outgoing, statement_closing, status, "
                + "reconciled_by, reconciled_at, reopened_movement_id, reopened_at) values ('" + tenantA + "', "
                + "date '2033-03-01', 0, 0, 0, 0, 'NEEDS_RECHECK', null, null, '" + movementB + "', now())");
        UUID ownReconciliation = ownerJdbc().queryForObject("select id from bank_reconciliation where tenant_id = ? "
                + "and month = date '2033-01-01'", UUID.class, tenantA);
        assertForeignKey("bank_difference_reconciliation_fk", "insert into bank_difference (tenant_id, "
                + "reconciliation_id, movement_id, reason) values ('" + tenantA + "', '" + reconciliationB() + "', '"
                + ownMovement + "', 'Fee')");
        assertForeignKey("bank_difference_movement_fk", "insert into bank_difference (tenant_id, "
                + "reconciliation_id, movement_id, reason) values ('" + tenantA + "', '" + ownReconciliation + "', '"
                + movementB + "', 'Fee')");
    }

    // ---- helpers

    private UUID reconciliationB() {
        return ownerJdbc().queryForObject("select id from bank_reconciliation where tenant_id = ? "
                + "and month = date '2033-01-01'", UUID.class, tenantB);
    }

    private UUID foreignPayment() {
        UUID plot = plot(tenantB);
        UUID month = billingMonth(tenantB, "2033-05-01");
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) values (?, ?, ?, 'PAYMENT', 'BANK', 1.00, date '2033-05-02', ?, ?)", id,
                tenantB, plot, month, UUID.randomUUID());
        return id;
    }

    private int count(UUID tenant, String table, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from " + table + " " + where,
                Integer.class));
    }

    private void assertForeignKey(String constraint, String statement) {
        Function<JdbcTemplate, Object> insert = sql -> sql.update(statement);
        assertThatThrownBy(() -> inTenant(tenantA, insert))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining(constraint);
    }
}
