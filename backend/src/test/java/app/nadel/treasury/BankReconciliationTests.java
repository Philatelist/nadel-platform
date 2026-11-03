package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The monthly bank reconciliation (feature 018 §2.7, §2.11, §2.14; worked examples 2.17 #7 and #8): the server
 * computes the books of a calendar month and the differences from the statement; «Звірено» needs every difference
 * 0.00; a bank difference no entry explains is booked by the chairman; a bank line entered later with a date in a
 * reconciled month (a payment through the payments screen, an expense, a reversal) sets the month back to «Потребує
 * повторної звірки» with that line; the bank card lists the months with their status. The examples run in the
 * previous month (its 1st is the opening date of both accounts), so a later entry with an earlier date is real.
 */
class BankReconciliationTests extends TreasuryApiSupport {

    String chairman;
    UUID chairmanId;
    String treasurer;
    UUID treasurerId;
    UUID cash;
    UUID bank;
    LocalDate opened;
    YearMonth month;

    @BeforeEach
    void accounts() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        chairmanId = lastPerson;
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        month = YearMonth.from(today()).minusMonths(1);
        opened = month.atDay(1);
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "20000.00", opened).andExpect(status().isOk());
    }

    /** Example 7's books: member bank payments of 2 000,00 and a handover of 1 000,00 in the month. */
    private void example7Books() throws Exception {
        UUID plot = paymentPlot(tenantA);
        pay(treasurer, plot, "BANK", "1200.00", opened.plusDays(4));
        pay(treasurer, plot, "BANK", "800.00", opened.plusDays(14));
        recordHandover(treasurer, handover(opened.plusDays(6), "1000.00", "КВ-7"));
    }

    @Test
    void example7TheFeeOnTheStatementIsADifferenceUntilTheTreasurerEntersItAsAnExpenseThenOctoberIsReconciled()
            throws Exception {
        example7Books();

        JsonNode before = body(reconciliation(treasurer, month).andExpect(status().isOk()));
        assertThat(before.get("month").asString()).isEqualTo(month.toString());
        assertThat(before.get("accountId").asString()).isEqualTo(bank.toString());
        assertThat(before.get("status").asString()).isEqualTo("NOT_RECONCILED");
        assertFigures(before.get("books"), "20000.00", "3000.00", "0.00", "23000.00");
        assertThat(before.has("statement")).isFalse();
        assertThat(before.has("differences")).isFalse();
        assertThat(before.get("balanced").asBoolean()).isFalse();
        assertThat(before.get("movementsTotal").asLong()).isEqualTo(3);
        assertThat(before.get("movements")).extracting(line -> line.get("kind").asString())
                .containsExactly("MEMBER_PAYMENT", "HANDOVER_IN", "MEMBER_PAYMENT");

        JsonNode entered = body(enterStatement(treasurer, month, "20000.00", "3000.00", "150.00", "22850.00")
                .andExpect(status().isOk()));
        assertThat(entered.get("status").asString()).isEqualTo("NOT_RECONCILED");
        assertFigures(entered.get("statement"), "20000.00", "3000.00", "150.00", "22850.00");
        assertFigures(entered.get("differences"), "0.00", "0.00", "150.00", "-150.00");
        assertThat(entered.get("balanced").asBoolean()).isFalse();
        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));

        recordExpense(treasurer, expense("BANK", month.atEndOfMonth(), "150.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));
        JsonNode explained = body(reconciliation(treasurer, month).andExpect(status().isOk()));
        assertFigures(explained.get("books"), "20000.00", "3000.00", "150.00", "22850.00");
        assertFigures(explained.get("differences"), "0.00", "0.00", "0.00", "0.00");
        assertThat(explained.get("balanced").asBoolean()).isTrue();

        JsonNode reconciled = body(confirmMonth(treasurer, month).andExpect(status().isOk()));
        assertThat(reconciled.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(reconciled.get("reconciledByName").asString()).startsWith("Скарбник");
        assertThat(reconciled.has("reconciledAt")).isTrue();
        assertThat(reconciled.has("reopened")).isFalse();
        assertThat(bankMonths()).containsEntry(month.toString(), "RECONCILED")
                .containsEntry(month.plusMonths(1).toString(), "NOT_RECONCILED");

        UUID id = reconciliationId(month);
        Map<String, Object> statement = awaitAudit(id, "BANK_STATEMENT_ENTERED", 1).getFirst();
        assertThat(statement).containsEntry("entity_type", "BANK_RECONCILIATION")
                .containsEntry("entity_label", month.toString()).containsEntry("actor_member_id", treasurerId);
        assertThat((String) statement.get("changes")).contains("\"outgoing\"", "150.00", "\"closing\"", "22850.00");
        assertThat((String) awaitAudit(id, "BANK_MONTH_RECONCILED", 1).getFirst().get("changes"))
                .contains("\"status\"", "NOT_RECONCILED", "RECONCILED", "\"incoming\"", "3000.00");
    }

    @Test
    void example8ABankPaymentDatedInTheReconciledMonthEnteredTodayReopensItWithThatLine() throws Exception {
        example7Books();
        recordExpense(treasurer, expense("BANK", month.atEndOfMonth(), "150.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));
        enterStatement(treasurer, month, "20000.00", "3000.00", "150.00", "22850.00").andExpect(status().isOk());
        confirmMonth(treasurer, month).andExpect(status().isOk());

        // entered today on the payments screen, dated the 20th of the reconciled month
        UUID payment = pay(treasurer, paymentPlot(tenantA), "BANK", "400.00", opened.plusDays(19));
        UUID line = ownerJdbc().queryForObject("select id from account_movement where payment_row_id = ?",
                UUID.class, payment);

        JsonNode reopened = body(reconciliation(treasurer, month).andExpect(status().isOk()));
        assertThat(reopened.get("status").asString()).isEqualTo("NEEDS_RECHECK");
        assertThat(reopened.get("reopened").get("movementId").asString()).isEqualTo(line.toString());
        assertThat(reopened.get("reopened").get("kind").asString()).isEqualTo("MEMBER_PAYMENT");
        assertThat(reopened.get("reopened").get("date").asString()).isEqualTo(opened.plusDays(19).toString());
        assertThat(reopened.get("reopened").get("amount").asString()).isEqualTo("400.00");
        assertThat(reopened.get("reopened").has("at")).isTrue();
        assertFigures(reopened.get("differences"), "0.00", "-400.00", "0.00", "-400.00");
        assertThat(reopened.get("balanced").asBoolean()).isFalse();
        assertThat(bankMonths()).containsEntry(month.toString(), "NEEDS_RECHECK");

        UUID id = reconciliationId(month);
        awaitAudit(id, "BANK_MONTH_REOPENED", 1);
        Map<String, Object> entry = ownerJdbc().queryForMap("select actor_member_id, actor_subject, entity_label, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? "
                + "and action = 'BANK_MONTH_REOPENED'", id);
        assertThat(entry.get("actor_member_id")).as("written by the system").isNull();
        assertThat(entry.get("actor_subject")).isEqualTo("system:treasury");
        assertThat(entry.get("entity_label")).isEqualTo(month.toString());
        assertThat((String) entry.get("changes")).contains("\"movement\"", line.toString(), "\"kind\"",
                "MEMBER_PAYMENT", "\"date\"", opened.plusDays(19).toString(), "\"amount\"", "400.00",
                "NEEDS_RECHECK");

        // the statement may be entered again, and the month reconciled again
        enterStatement(treasurer, month, "20000.00", "3400.00", "150.00", "23250.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_RECHECK"))
                .andExpect(jsonPath("$.balanced").value(true));
        JsonNode again = body(confirmMonth(treasurer, month).andExpect(status().isOk()));
        assertThat(again.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(again.get("reopened").get("movementId").asString()).as("the last reopening stays")
                .isEqualTo(line.toString());
    }

    @Test
    void aCashLineAndALineOfAnotherMonthDoNotReopenAReconciledMonth() throws Exception {
        enterStatement(treasurer, month, "20000.00", "0.00", "0.00", "20000.00").andExpect(status().isOk());
        confirmMonth(treasurer, month).andExpect(status().isOk());

        UUID plot = paymentPlot(tenantA);
        pay(treasurer, plot, "CASH", "50.00", opened.plusDays(3));
        pay(treasurer, plot, "BANK", "60.00", today());

        assertThat(body(reconciliation(treasurer, month)).get("status").asString()).isEqualTo("RECONCILED");
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'BANK_MONTH_REOPENED'", Integer.class, tenantA)).isZero();
    }

    @Test
    void theChairmanBooksABankDifferenceOnTheMonthsLastDayWhichClosesItAndCanBeReversedToday() throws Exception {
        enterStatement(treasurer, month, "20000.00", "0.00", "20.00", "19980.00").andExpect(status().isOk());

        bookBankDifference(chairman, month, "0.00", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"))
                .andExpect(jsonPath("$.errors[1].field").value("reason"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"));
        JsonNode booked = body(bookBankDifference(chairman, month, "-20.00", " Комісія банку за жовтень ")
                .andExpect(status().isOk()));

        assertFigures(booked.get("books"), "20000.00", "0.00", "20.00", "19980.00");
        assertFigures(booked.get("differences"), "0.00", "0.00", "0.00", "0.00");
        assertThat(booked.get("balanced").asBoolean()).isTrue();
        JsonNode line = booked.get("movements").get(0);
        assertThat(line.get("kind").asString()).isEqualTo("BANK_DIFFERENCE");
        assertThat(line.get("amount").asString()).isEqualTo("-20.00");
        assertThat(line.get("date").asString()).isEqualTo(month.atEndOfMonth().toString());
        assertThat(line.get("bookingMonth").asString()).isEqualTo(month.toString());
        assertThat(line.get("reason").asString()).isEqualTo("Комісія банку за жовтень");
        assertThat(line.get("source").get("type").asString()).isEqualTo("BANK_RECONCILIATION");
        UUID id = reconciliationId(month);
        assertThat(line.get("source").get("id").asString()).isEqualTo(id.toString());
        assertThat(accountOf(body(accounts(treasurer)), "BANK").get("balance").asString()).isEqualTo("19980.00");
        Map<String, Object> audit = awaitAudit(id, "BANK_DIFFERENCE_BOOKED", 1).getFirst();
        assertThat(audit).containsEntry("actor_member_id", chairmanId);
        assertThat((String) audit.get("changes")).contains("\"amount\"", "-20.00", "\"date\"",
                month.atEndOfMonth().toString(), "\"reason\"", "Комісія банку за жовтень");

        // the current month: dated today, and only once its statement is entered
        YearMonth current = YearMonth.from(today());
        bookBankDifference(chairman, current, "5.00", "Відсотки").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_STATEMENT_MISSING"));
        enterStatement(treasurer, current, "19980.00", "5.00", "0.00", "19985.00").andExpect(status().isOk());
        JsonNode today = body(bookBankDifference(chairman, current, "5.00", "Відсотки").andExpect(status().isOk()));
        assertThat(today.get("movements").get(0).get("date").asString()).isEqualTo(today().toString());
        assertThat(today.get("balanced").asBoolean()).isTrue();

        // reversed through the journal: dated today, never guarded, audited about the month
        UUID lineId = UUID.fromString(line.get("id").asString());
        JsonNode reversal = body(reverseMovement(chairman, bank, lineId, "Банк повернув комісію")
                .andExpect(status().isOk()));
        assertThat(reversal.get("kind").asString()).isEqualTo("BANK_DIFFERENCE");
        assertThat(reversal.get("amount").asString()).isEqualTo("20.00");
        assertThat(reversal.get("date").asString()).isEqualTo(today().toString());
        assertThat(reversal.get("reversesMovementId").asString()).isEqualTo(lineId.toString());
        assertThat(reversal.get("reason").asString()).isEqualTo("Банк повернув комісію");
        reverseMovement(chairman, bank, lineId, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        assertThat((String) awaitAudit(id, "BANK_DIFFERENCE_REVERSED", 1).getFirst().get("changes"))
                .contains("\"amount\"", "-20.00", "\"reversedOn\"", today().toString(), "Банк повернув комісію");
        JsonNode stillBalanced = body(reconciliation(treasurer, month));
        assertThat(stillBalanced.get("movements").get(0).get("reversed").asBoolean()).isTrue();
        assertThat(stillBalanced.get("balanced").asBoolean()).as("the reversal is dated in the current month")
                .isTrue();
    }

    @Test
    void theReversalOfABankDifferenceDatedTodayReopensTheReconciledCurrentMonth() throws Exception {
        enterStatement(treasurer, month, "20000.00", "0.00", "20.00", "19980.00").andExpect(status().isOk());
        JsonNode booked = body(bookBankDifference(chairman, month, "-20.00", "Комісія").andExpect(status().isOk()));
        UUID lineId = UUID.fromString(booked.get("movements").get(0).get("id").asString());
        YearMonth current = YearMonth.from(today());
        enterStatement(treasurer, current, "19980.00", "0.00", "0.00", "19980.00").andExpect(status().isOk());
        confirmMonth(treasurer, current).andExpect(status().isOk());

        UUID reversal = UUID.fromString(body(reverseMovement(chairman, bank, lineId, "Помилка")
                .andExpect(status().isOk())).get("id").asString());

        JsonNode reopened = body(reconciliation(treasurer, current));
        assertThat(reopened.get("status").asString()).isEqualTo("NEEDS_RECHECK");
        assertThat(reopened.get("reopened").get("movementId").asString()).isEqualTo(reversal.toString());
        assertThat(reopened.get("reopened").get("kind").asString()).isEqualTo("BANK_DIFFERENCE");
        assertThat(body(reconciliation(treasurer, month)).get("status").asString()).isEqualTo("NOT_RECONCILED");
    }

    @Test
    void theStatusMachineRefusesASecondConfirmationAndChangesToAReconciledMonthUntilALineReopensIt()
            throws Exception {
        enterStatement(treasurer, month, "20000.00", "0.00", "0.00", "20000.00").andExpect(status().isOk());
        confirmMonth(treasurer, month).andExpect(status().isOk());

        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_RECONCILED"));
        enterStatement(treasurer, month, "20000.00", "1.00", "0.00", "20001.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_RECONCILED"));
        bookBankDifference(chairman, month, "1.00", "Відсотки").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_RECONCILED"));

        // an expense of another writer dated in the month reopens it
        recordExpense(treasurer, expense("BANK", opened.plusDays(9), "15.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));
        JsonNode reopened = body(reconciliation(treasurer, month));
        assertThat(reopened.get("status").asString()).isEqualTo("NEEDS_RECHECK");
        assertThat(reopened.get("reopened").get("kind").asString()).isEqualTo("EXPENSE");
        assertThat(reopened.get("reopened").get("amount").asString()).isEqualTo("-15.00");
        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));
        // the fee is on the bank's corrected statement: re-entered while NEEDS_RECHECK, then reconciled again
        enterStatement(treasurer, month, "20000.00", "0.00", "15.00", "19985.00").andExpect(status().isOk());
        confirmMonth(treasurer, month).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECONCILED"));
        assertThat(ownerJdbc().queryForObject("select status from bank_reconciliation where id = ?", String.class,
                reconciliationId(month))).isEqualTo("RECONCILED");
    }

    @Test
    void confirmIsRefusedWithoutAStatementAndWhileADifferenceIsNot0() throws Exception {
        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));

        enterStatement(treasurer, month, "20000.00", "0.00", "0.01", "19999.99").andExpect(status().isOk());

        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));
        assertThat(ownerJdbc().queryForObject("select status from bank_reconciliation where id = ?", String.class,
                reconciliationId(month))).isEqualTo("NOT_RECONCILED");
    }

    @Test
    void example9AStatementThatDoesNotAddUpIsRefusedAndNothingIsStored() throws Exception {
        enterStatement(treasurer, month, "100.00", "50.00", "20.00", "200.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_INCONSISTENT"))
                .andExpect(jsonPath("$.errors[0].field").value("closing"));
        enterStatement(treasurer, month, "100.00", "-50.00", "20.00", "30.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("incoming"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        enterStatement(treasurer, month, "100.00", "50.00", "20.00", "1.5").andExpect(status().isBadRequest());

        assertThat(ownerJdbc().queryForObject("select count(*) from bank_reconciliation where tenant_id = ?",
                Integer.class, tenantA)).isZero();
    }

    @Test
    void aFutureMonthAMonthBeforeTheOpeningAndABankWithoutOpeningAreRefused() throws Exception {
        reconciliation(treasurer, YearMonth.from(today()).plusMonths(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("month"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        reconciliation(treasurer, "2026-13").andExpect(status().isBadRequest());
        reconciliation(treasurer, month.minusMonths(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        enterStatement(treasurer, month.minusMonths(1), "0.00", "0.00", "0.00", "0.00")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"));

        String other = person(tenantB, "TREASURER");
        reconciliation(other, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        confirmMonth(other, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        assertThat(accountOf(body(accounts(other)), "BANK").get("months")).isEmpty();
    }

    @Test
    void theBankCardListsTheMonthsFromTheOpeningWithLinesOrAReconciliationAndTheCurrentMonth() throws Exception {
        JsonNode cashCard = accountOf(body(accounts(treasurer)), "CASH");
        assertThat(cashCard.get("months")).isEmpty();
        assertThat(bankMonths()).containsExactly(Map.entry(month.plusMonths(1).toString(), "NOT_RECONCILED"));

        recordExpense(treasurer, expense("BANK", opened.plusDays(2), "5.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));

        assertThat(bankMonths()).containsExactly(Map.entry(month.plusMonths(1).toString(), "NOT_RECONCILED"),
                Map.entry(month.toString(), "NOT_RECONCILED"));
        assertThat(accountOf(body(accounts(treasurer, opened.plusDays(3))), "BANK").get("months"))
                .extracting(m -> m.get("month").asString()).containsExactly(month.toString());
    }

    // ---- helpers

    private void assertFigures(JsonNode figures, String opening, String incoming, String outgoing, String closing) {
        assertThat(figures.get("opening").asString()).as("opening").isEqualTo(opening);
        assertThat(figures.get("incoming").asString()).as("incoming").isEqualTo(incoming);
        assertThat(figures.get("outgoing").asString()).as("outgoing").isEqualTo(outgoing);
        assertThat(figures.get("closing").asString()).as("closing").isEqualTo(closing);
    }

    /** The months of the bank card, newest first, with their status. */
    private Map<String, String> bankMonths() throws Exception {
        Map<String, String> months = new java.util.LinkedHashMap<>();
        for (JsonNode m : accountOf(body(accounts(treasurer)), "BANK").get("months")) {
            months.put(m.get("month").asString(), m.get("status").asString());
        }
        return months;
    }

    private UUID reconciliationId(YearMonth month) {
        return ownerJdbc().queryForObject("select id from bank_reconciliation where tenant_id = ? and month = ?",
                UUID.class, tenantA, month.atDay(1));
    }
}
