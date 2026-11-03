package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Handovers of cash to the bank (feature 018 §2.2, §2.4, §2.8, §2.14; worked example 2.17 #3): one handover moves the
 * money out of the cash and into the bank on the same day with the deposit slip number, never takes the cash below zero
 * on its day or a later day, belongs to the earliest open month when dated in a closed one, and its reversal (dated
 * today, once, with a reason) gives both accounts their balances back, even when the bank goes below zero.
 */
class HandoversTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    UUID treasurerId;
    UUID cash;
    UUID bank;
    LocalDate opened;

    @BeforeEach
    void cashBook() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        opened = today().minusMonths(1).withDayOfMonth(1);
    }

    @Test
    void example3AHandoverOf1000LeavesCash200AndBank21000AndBothJournalsShowItsDocumentNumber() throws Exception {
        open("1200.00", "20000.00");

        JsonNode created = body(createHandover(treasurer, handover(today(), "1000.00", " КВ-123 "))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asString());

        assertThat(created.get("amount").asString()).isEqualTo("1000.00");
        assertThat(created.get("documentNumber").asString()).isEqualTo("КВ-123");
        assertThat(created.get("date").asString()).isEqualTo(today().toString());
        assertThat(created.get("bookingMonth").asString()).isEqualTo(YearMonth.from(today()).toString());
        assertThat(created.get("reversed").asBoolean()).isFalse();
        assertThat(created.get("createdByName").asString()).startsWith("Скарбник");
        balances("200.00", "21000.00");
        for (UUID account : List.of(cash, bank)) {
            assertThat(journal(treasurer, account, opened)).singleElement().satisfies(line -> {
                assertThat(line.get("kind").asString()).isEqualTo(account.equals(cash) ? "HANDOVER_OUT"
                        : "HANDOVER_IN");
                assertThat(line.get("amount").asString()).isEqualTo(account.equals(cash) ? "-1000.00" : "1000.00");
                assertThat(line.get("date").asString()).isEqualTo(today().toString());
                assertThat(line.get("documentNumber").asString()).isEqualTo("КВ-123");
                assertThat(line.get("source").get("type").asString()).isEqualTo("HANDOVER");
                assertThat(line.get("source").get("id").asString()).isEqualTo(id.toString());
            });
        }
        Map<String, Object> entry = awaitAudit(id, "HANDOVER_RECORDED", 1).getFirst();
        assertThat(entry).containsEntry("entity_type", "HANDOVER").containsEntry("entity_label", "КВ-123")
                .containsEntry("actor_member_id", treasurerId);
        assertThat((String) entry.get("changes")).contains("\"amount\"", "1000.00", "\"documentNumber\"", "КВ-123",
                "\"date\"", today().toString());
    }

    @Test
    void theReversalRestoresBothAccountsIsDatedTodayAndHappensOnce() throws Exception {
        open("1200.00", "20000.00");
        UUID id = recordHandover(treasurer, handover(opened.plusDays(6), "1000.00", "КВ-123"));
        balances("200.00", "21000.00");

        reverseHandover(chairman, id, "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        JsonNode reversed = body(reverseHandover(chairman, id, " Помилкова сума ").andExpect(status().isOk()));

        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        assertThat(reversed.get("reversal").get("date").asString()).isEqualTo(today().toString());
        assertThat(reversed.get("reversal").get("reason").asString()).isEqualTo("Помилкова сума");
        assertThat(reversed.get("reversal").get("createdByName").asString()).startsWith("Скарбник");
        balances("1200.00", "20000.00");
        for (UUID account : List.of(cash, bank)) {
            JsonNode lines = journal(treasurer, account, opened);
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0).get("reversed").asBoolean()).isTrue();
            JsonNode reversal = lines.get(1);
            assertThat(reversal.get("date").asString()).isEqualTo(today().toString());
            assertThat(reversal.get("amount").asString()).isEqualTo(account.equals(cash) ? "1000.00" : "-1000.00");
            assertThat(reversal.get("reversesMovementId").asString()).isEqualTo(lines.get(0).get("id").asString());
            assertThat(reversal.get("reason").asString()).isEqualTo("Помилкова сума");
            assertThat(reversal.get("documentNumber").asString()).isEqualTo("КВ-123");
        }
        Map<String, Object> row = ownerJdbc().queryForMap("select id, amount::text as amount, handed_on::text as day, "
                + "document_number, reason from handover where reverses_handover_id = ?", id);
        assertThat(row).containsEntry("amount", "1000.00").containsEntry("day", today().toString())
                .containsEntry("document_number", "КВ-123").containsEntry("reason", "Помилкова сума");

        reverseHandover(chairman, id, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        reverseHandover(chairman, (UUID) row.get("id"), "Сторно сторно").andExpect(status().isNotFound());
        reverseHandover(chairman, UUID.randomUUID(), "Немає").andExpect(status().isNotFound());
        assertThat(storedMovements(tenantA)).hasSize(4);
        Map<String, Object> entry = awaitAudit(id, "HANDOVER_REVERSED", 1).getFirst();
        assertThat(entry).containsEntry("entity_label", "КВ-123");
        assertThat((String) entry.get("changes")).contains("\"reason\"", "Помилкова сума", "\"reversedOn\"",
                today().toString());
    }

    @Test
    void theReversalMayTakeTheBankBelowZero() throws Exception {
        open("500.00", "0.00");
        UUID id = recordHandover(treasurer, handover(today(), "300.00", "КВ-7"));
        recordExpense(treasurer, expense("BANK", today(), "300.00", category(tenantA, "EXPENSE", "BANK_FEES")));
        balances("200.00", "0.00");

        reverseHandover(chairman, id, "Не той день").andExpect(status().isOk());

        balances("500.00", "-300.00");
        assertThat(accountOf(body(accounts(treasurer)), "BANK").get("negative").asBoolean()).isTrue();
    }

    @Test
    void aHandoverNeverTakesTheCashBelowZeroOnItsDayOrALaterDay() throws Exception {
        open("1200.00", "20000.00");
        LocalDate sixth = opened.plusDays(5);
        LocalDate seventh = opened.plusDays(6);
        recordHandover(treasurer, handover(seventh, "1000.00", "КВ-1"));

        createHandover(treasurer, handover(seventh, "250.00", "КВ-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.date").value(seventh.toString()));
        // back-dated: its own day stays positive (800,00), the later one would be -200,00
        createHandover(treasurer, handover(sixth, "400.00", "КВ-3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.date").value(seventh.toString()));

        assertThat(storedMovements(tenantA)).as("nothing of the refused handovers is stored").hasSize(2);
        assertThat(ownerJdbc().queryForObject("select count(*) from handover where tenant_id = ?", Integer.class,
                tenantA)).isOne();
        createHandover(treasurer, handover(sixth, "200.00", "КВ-4")).andExpect(status().isCreated());
        balances("0.00", "21200.00");
    }

    @Test
    void theFieldsAndBothOpeningBalancesAreChecked() throws Exception {
        expectField(handover(today(), "0.00", "КВ-1"), "amount", "AMOUNT_NOT_POSITIVE");
        expectField(handover(today(), "-5.00", "КВ-1"), "amount", "AMOUNT_NOT_POSITIVE");
        expectField(handover(today(), "1000000000000.00", "КВ-1"), "amount", "INVALID");
        expectField(handover(today().plusDays(1), "5.00", "КВ-1"), "date", "DATE_IN_FUTURE");
        expectField(handover(today(), "5.00", "   "), "documentNumber", "REQUIRED");
        expectField(handover(today(), "5.00", "К".repeat(65)), "documentNumber", "TOO_LONG");
        Map<String, Object> noNumber = handover(today(), "5.00", "КВ-1");
        noNumber.remove("documentNumber");
        expectField(noNumber, "documentNumber", "REQUIRED");

        // neither account has an opening balance, then only the cash
        createHandover(treasurer, handover(today(), "5.00", "КВ-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(chairman, cash, "100.00", opened).andExpect(status().isOk());
        createHandover(treasurer, handover(today(), "5.00", "КВ-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        // the bank opened later than the cash: a handover before the bank's opening date is refused
        setOpening(chairman, bank, "0.00", opened.plusDays(3)).andExpect(status().isOk());
        createHandover(treasurer, handover(opened.plusDays(2), "5.00", "КВ-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        createHandover(treasurer, handover(opened.minusDays(1), "5.00", "КВ-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"));
        assertThat(storedMovements(tenantA)).isEmpty();
        createHandover(treasurer, handover(opened.plusDays(3), "5.00", "КВ-1")).andExpect(status().isCreated());
    }

    @Test
    void aHandoverDatedInAClosedMonthBelongsToTheEarliestOpenMonthAndKeepsItsDate() throws Exception {
        open("1000.00", "0.00");
        LocalDate lastOfClosed = today().withDayOfMonth(1).minusDays(1);
        YearMonth closed = YearMonth.from(lastOfClosed);
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenantA, closed.atDay(1));
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?", tenantA, closed.atDay(1));

        createHandover(treasurer, handover(lastOfClosed, "40.00", "КВ-9")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(lastOfClosed.toString()))
                .andExpect(jsonPath("$.bookingMonth").value(YearMonth.from(today()).toString()));

        assertThat(storedMovements(tenantA)).hasSize(2).allSatisfy(line -> assertThat(line)
                .containsEntry("moved_on", lastOfClosed.toString())
                .containsEntry("booking_month", today().withDayOfMonth(1).toString()));
    }

    // ---- helpers

    private void open(String cashAmount, String bankAmount) throws Exception {
        setOpening(chairman, cash, cashAmount, opened).andExpect(status().isOk());
        setOpening(chairman, bank, bankAmount, opened).andExpect(status().isOk());
    }

    private void balances(String cashBalance, String bankBalance) throws Exception {
        JsonNode accounts = body(accounts(treasurer).andExpect(status().isOk()));
        assertThat(accountOf(accounts, "CASH").get("balance").asString()).isEqualTo(cashBalance);
        assertThat(accountOf(accounts, "BANK").get("balance").asString()).isEqualTo(bankBalance);
    }

    private void expectField(Map<String, Object> input, String field, String code) throws Exception {
        createHandover(treasurer, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItem(field)))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code")
                        .value(Matchers.hasItem(code)));
    }
}
