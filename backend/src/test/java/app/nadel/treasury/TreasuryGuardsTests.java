package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guards of the treasury tables: the five append-only tables (privilege for nadel_app, trigger for the owner), the
 * write-once opening balance, the movement reversal rule, the booked-once count difference, the status machine of the
 * bank reconciliation and the column grants of the categories.
 */
class TreasuryGuardsTests extends TreasuryTestSupport {

    private static final String PRIVILEGE = "42501";
    private static final String CHECK = "23514";

    private UUID cash;
    private UUID bank;
    private UUID movement;
    private UUID expense;

    @BeforeEach
    void createFixtures() {
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        expense = expense(tenantA);
        movement = ownerJdbc().queryForObject("select movement_id from expense where id = ?", UUID.class, expense);
        attachment(tenantA, expense, 1);
        otherIncome(tenantA);
        handover(tenantA);
    }

    // ---- append-only

    @Test
    void theApplicationRoleCannotUpdateDeleteOrTruncateTheAppendOnlyTables() {
        for (String table : List.of("account_movement", "handover", "expense", "expense_attachment", "other_income")) {
            assertState(PRIVILEGE, table, sql -> sql.update("update " + table + " set tenant_id = tenant_id"));
            assertState(PRIVILEGE, table, sql -> sql.update("delete from " + table));
            assertState(PRIVILEGE, table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
    }

    @Test
    void theApplicationRoleMayInsertAndReadMovements() {
        inTenant(tenantA, sql -> sql.update("insert into account_movement (tenant_id, account_id, kind, amount, "
                + "moved_on, booking_month, source_type, source_id) values (?, ?, 'EXPENSE', -1.00, date '2033-02-01', "
                + "date '2033-02-01', 'EXPENSE', gen_random_uuid())", tenantA, cash));

        assertThat(intIn(tenantA, sql -> sql.queryForObject("select count(*) from account_movement "
                + "where moved_on = date '2033-02-01'", Integer.class))).isEqualTo(1);
    }

    @Test
    void theOwnerIsRefusedByTheTriggerOnEveryAppendOnlyTable() {
        for (String table : List.of("account_movement", "handover", "expense", "expense_attachment", "other_income")) {
            assertThatThrownBy(() -> ownerJdbc().update("update " + table + " set tenant_id = tenant_id"))
                    .as(table).hasStackTraceContaining("append-only");
            assertThatThrownBy(() -> ownerJdbc().update("delete from " + table))
                    .as(table).hasStackTraceContaining("append-only");
            assertThatThrownBy(() -> ownerJdbc().execute("truncate " + table + " cascade"))
                    .as(table).hasStackTraceContaining("append-only");
            // nadel_owner without the maintenance setting is refused too
            assertThatThrownBy(() -> asNadelOwner(false, sql -> {
                sql.update("delete from " + table);
            }))
                    .as(table).hasStackTraceContaining("append-only");
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from account_movement where tenant_id = ?",
                Integer.class, tenantA)).isPositive();
    }

    @Test
    void theOwnerMayMaintainUnderTheMaintenanceSetting() {
        UUID extra = movement(tenantA, cash, "EXPENSE", "-2.00", "2033-02-02", "2033-02-01");

        asNadelOwner(true, sql -> sql.update("delete from account_movement where id = ?", extra));

        assertThat(ownerJdbc().queryForObject("select count(*) from account_movement where id = ?", Integer.class,
                extra)).isZero();
    }

    // ---- movement reversal rule

    @Test
    void aReversalHasTheNegatedAmountTheSameAccountAndKind() {
        String head = "insert into account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, "
                + "source_type, source_id, reverses_movement_id, reason) values ('" + tenantA + "', ";
        String tail = ", date '2033-01-15', date '2033-01-01', 'EXPENSE', gen_random_uuid(), '" + movement
                + "', 'Wrong')";

        assertGuard("account_movement_target_guard", head + "'" + cash + "', 'EXPENSE', 10.01" + tail);
        assertGuard("account_movement_target_guard", head + "'" + bank + "', 'EXPENSE', 10.00" + tail);
        assertGuard("account_movement_target_guard", head + "'" + cash + "', 'OTHER_INCOME', 10.00" + tail);

        UUID reversal = reversalOf(tenantA, movement);
        assertThat(ownerJdbc().queryForObject("select amount from account_movement where id = ?", java.math.BigDecimal.class,
                reversal)).isEqualByComparingTo("10.00");
    }

    @Test
    void aMovementIsReversedAtMostOnceAndAPaymentRowHasAtMostOneMovement() {
        reversalOf(tenantA, movement);

        assertThatThrownBy(() -> reversalOf(tenantA, movement))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("account_movement_reversed_once_uq");
    }

    @Test
    void aReversalNeedsAReasonAndAnOriginalHasNone() {
        assertThatThrownBy(() -> ownerJdbc().update("insert into account_movement (tenant_id, account_id, kind, amount, "
                + "moved_on, booking_month, source_type, source_id, reverses_movement_id) select tenant_id, account_id, "
                + "kind, -amount, moved_on, booking_month, source_type, source_id, id from account_movement "
                + "where id = ?", movement))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("account_movement_reason_ck");
        assertThatThrownBy(() -> ownerJdbc().update("insert into account_movement (tenant_id, account_id, kind, amount, "
                + "moved_on, booking_month, source_type, source_id, reason) select tenant_id, account_id, kind, amount, "
                + "moved_on, booking_month, source_type, source_id, 'No' from account_movement where id = ?", movement))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("account_movement_reason_ck");
    }

    // ---- opening balance: write once

    @Test
    void theOpeningBalanceIsWrittenOnceByTheApplicationRole() {
        UUID chairman = member(tenantA);
        String write = "update money_account set opening_amount = 100.50, opening_date = date '2033-01-01', "
                + "opening_set_by = ?, opening_set_at = now() where id = ?";

        assertThat(intIn(tenantA, sql -> sql.update(write, chairman, cash))).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select opening_amount from money_account where id = ?",
                java.math.BigDecimal.class, cash)).isEqualByComparingTo("100.50");

        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(write, chairman, cash)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("the opening balance is already set");
        // the owner is held to the same rule
        assertThatThrownBy(() -> ownerJdbc().update(write, chairman, cash))
                .hasMessageContaining("the opening balance is already set");
        assertThat(ownerJdbc().queryForObject("select opening_amount from money_account where id = ?",
                java.math.BigDecimal.class, cash)).isEqualByComparingTo("100.50");
    }

    @Test
    void anOpeningBalanceIsAllOrNothingAndTheKindCannotChange() {
        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(
                "update money_account set opening_amount = 1.00 where id = ?", cash)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("money_account_opening_ck");
        assertState(PRIVILEGE, "money_account", sql -> sql.update("update money_account set kind = 'BANK' where id = ?",
                cash));
        assertState(PRIVILEGE, "money_account", sql -> sql.update("delete from money_account where id = ?", cash));
    }

    // ---- booked once

    @Test
    void theDifferenceOfACountIsBookedOnce() {
        UUID count = cashCount(tenantA);
        UUID booked = movement(tenantA, cash, "COUNT_DIFFERENCE", "-5.00", "2033-01-16", "2033-01-01");
        UUID second = movement(tenantA, cash, "COUNT_DIFFERENCE", "-1.00", "2033-01-17", "2033-01-01");

        assertThat(intIn(tenantA, sql -> sql.update("update cash_count set booked_movement_id = ? where id = ?",
                booked, count))).isEqualTo(1);

        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(
                "update cash_count set booked_movement_id = ? where id = ?", second, count)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("the difference is booked once");
        assertThatThrownBy(() -> ownerJdbc().update("update cash_count set booked_movement_id = null where id = ?",
                count)).hasMessageContaining("the difference is booked once");
        assertThat(ownerJdbc().queryForObject("select booked_movement_id from cash_count where id = ?", UUID.class,
                count)).isEqualTo(booked);
    }

    @Test
    void aCountKeepsItsFiguresAndAnExplanationIsRequiredForADifference() {
        UUID count = cashCount(tenantA);

        assertState(PRIVILEGE, "cash_count", sql -> sql.update("update cash_count set counted_amount = 1.00 "
                + "where id = ?", count));
        assertThatThrownBy(() -> ownerJdbc().update("update cash_count set explanation = 'Edited' where id = ?",
                count)).hasMessageContaining("only the booked movement can be written");
        assertThatThrownBy(() -> ownerJdbc().update("insert into cash_count (tenant_id, account_id, counted_at, "
                + "counted_on, counted_amount, book_balance, difference) values (?, ?, now(), current_date, 9.00, "
                + "10.00, -1.00)", tenantA, cash))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("cash_count_explanation_ck");
    }

    // ---- bank reconciliation status machine

    @Test
    void theStatusMachineAllowsOnlyTheDefinedTransitions() {
        UUID member = member(tenantA);
        UUID reconciliation = bankReconciliation(tenantA, "2033-02-01");
        UUID bankMovement = movement(tenantA, bank, "OTHER_INCOME", "7.00", "2033-02-03", "2033-02-01");

        // NOT_RECONCILED cannot jump to NEEDS_RECHECK
        assertTransition(reconciliation, "status = 'NEEDS_RECHECK', reopened_movement_id = '" + bankMovement
                + "', reopened_at = now()", "NOT_RECONCILED to NEEDS_RECHECK");
        // NOT_RECONCILED -> RECONCILED
        assertThat(intIn(tenantA, sql -> sql.update("update bank_reconciliation set status = 'RECONCILED', "
                + "reconciled_by = ?, reconciled_at = now() where id = ?", member, reconciliation))).isEqualTo(1);
        // a reconciled month keeps its statement
        assertTransition(reconciliation, "statement_opening = 90.00, statement_closing = 120.00",
                "a reconciled month keeps its statement");
        // RECONCILED -> NOT_RECONCILED is not a transition
        assertTransition(reconciliation, "status = 'NOT_RECONCILED', reconciled_by = null, reconciled_at = null",
                "RECONCILED to NOT_RECONCILED");
        // RECONCILED -> NEEDS_RECHECK
        assertThat(intIn(tenantA, sql -> sql.update("update bank_reconciliation set status = 'NEEDS_RECHECK', "
                + "reopened_movement_id = ?, reopened_at = now() where id = ?", bankMovement, reconciliation)))
                .isEqualTo(1);
        // NEEDS_RECHECK -> NOT_RECONCILED is not a transition
        assertTransition(reconciliation, "status = 'NOT_RECONCILED', reconciled_by = null, reconciled_at = null, "
                + "reopened_movement_id = null, reopened_at = null", "NEEDS_RECHECK to NOT_RECONCILED");
        // the statement may be corrected while the month needs a recheck, then NEEDS_RECHECK -> RECONCILED
        assertThat(intIn(tenantA, sql -> sql.update("update bank_reconciliation set statement_incoming = 57.00, "
                + "statement_closing = 137.00 where id = ?", reconciliation))).isEqualTo(1);
        assertThat(intIn(tenantA, sql -> sql.update("update bank_reconciliation set status = 'RECONCILED', "
                + "reconciled_by = ?, reconciled_at = now() where id = ?", member, reconciliation))).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select status from bank_reconciliation where id = ?", String.class,
                reconciliation)).isEqualTo("RECONCILED");
    }

    @Test
    void aReconciliationKeepsItsMonthAndTheStatementAddsUp() {
        UUID reconciliation = bankReconciliation(tenantA, "2033-03-01");

        assertState(PRIVILEGE, "bank_reconciliation", sql -> sql.update(
                "update bank_reconciliation set month = date '2033-04-01' where id = ?", reconciliation));
        assertState(PRIVILEGE, "bank_reconciliation", sql -> sql.update("delete from bank_reconciliation "
                + "where id = ?", reconciliation));
        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(
                "update bank_reconciliation set statement_closing = 1.00 where id = ?", reconciliation)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining("bank_reconciliation_figures_ck");
        assertThatThrownBy(() -> ownerJdbc().update("update bank_reconciliation set month = date '2033-04-01' "
                + "where id = ?", reconciliation)).hasMessageContaining("the month and the owner");
    }

    // ---- categories

    @Test
    void theApplicationRoleMayChangeOnlyNameSortOrderAndArchived() {
        UUID water = category(tenantA, "EXPENSE", "WATER");

        assertThat(intIn(tenantA, sql -> sql.update("update expense_category set name = 'Water', sort_order = 7, "
                + "archived = true where id = ?", water))).isEqualTo(1);
        assertState(PRIVILEGE, "expense_category", sql -> sql.update("update expense_category set kind = 'INCOME' "
                + "where id = ?", water));
        assertState(PRIVILEGE, "expense_category", sql -> sql.update("update expense_category set system_code = 'X' "
                + "where id = ?", water));
        assertState(PRIVILEGE, "expense_category", sql -> sql.update("update expense_category set tenant_id = tenant_id "
                + "where id = ?", water));
        assertState(PRIVILEGE, "expense_category", sql -> sql.update("delete from expense_category where id = ?", water));
        assertState(PRIVILEGE, "expense_category", sql -> {
            sql.execute("truncate expense_category");
            return null;
        });
    }

    @Test
    void aCategoryNameIsUniquePerKindIgnoringCase() {
        UUID water = category(tenantA, "EXPENSE", "WATER");
        UUID waste = category(tenantA, "EXPENSE", "WASTE");
        inTenant(tenantA, sql -> sql.update("update expense_category set name = 'Water supply' where id = ?", water));

        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(
                "update expense_category set name = 'WATER SUPPLY' where id = ?", waste)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("expense_category_name_uq");
        // the same name is fine in the other kind and in another cooperative
        inTenant(tenantA, sql -> sql.update("update expense_category set name = 'Water supply' where id = ?",
                category(tenantA, "INCOME", "SALES")));
        inTenant(tenantB, sql -> sql.update("update expense_category set name = 'Water supply' where id = ?",
                category(tenantB, "EXPENSE", "WATER")));
    }

    // ---- attachments and storage

    @Test
    void anAttachmentHasAtMostFivePositionsAnAllowedTypeAndASizeThatMatches() {
        UUID id = expense;
        String insert = "insert into expense_attachment (tenant_id, expense_id, position, content_type, file_name, "
                + "size_bytes, sha256, content) values ('" + tenantA + "', '" + id + "', %d, '%s', 'a', %d, "
                + "decode(repeat('ab', 32), 'hex'), '\\x25504446'::bytea)";

        assertCheck("expense_attachment_position_ck", insert.formatted(6, "application/pdf", 4));
        assertCheck("expense_attachment_type_ck", insert.formatted(2, "text/html", 4));
        assertCheck("expense_attachment_size_ck", insert.formatted(2, "application/pdf", 5));
        assertThatThrownBy(() -> ownerJdbc().update(insert.formatted(1, "application/pdf", 4)))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("expense_attachment_position_uq");
        assertThat(ownerJdbc().queryForObject("select attstorage::text from pg_attribute where attrelid = "
                + "'expense_attachment'::regclass and attname = 'content'", String.class)).isEqualTo("e");
    }

    // ---- helpers

    private void assertState(String sqlState, String table, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(tenantA, statement))
                .as(table)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(sqlState));
    }

    private void assertGuard(String constraint, String statement) {
        assertThatThrownBy(() -> ownerJdbc().update(statement))
                .as(constraint)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK))
                .hasMessageContaining(constraint);
    }

    private void assertCheck(String constraint, String statement) {
        assertGuard(constraint, statement);
    }

    private void assertTransition(UUID reconciliation, String set, String what) {
        assertThatThrownBy(() -> inTenant(tenantA, sql -> sql.update(
                "update bank_reconciliation set " + set + " where id = ?", reconciliation)))
                .as(what)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK));
    }
}
