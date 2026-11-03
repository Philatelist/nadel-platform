package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Other income (feature 018 §2.2, §2.5, §2.8, §2.14): bank interest goes into the bank with its income category; the
 * category must be a non-archived income category; a closed month books the income in the earliest open month; the
 * reversal of a cash income is refused when it would make the cash negative, the reversal of a bank income is not; an
 * income is reversed once; the description is never stored, audited or published in the clear.
 */
class OtherIncomesTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    UUID treasurerId;
    UUID cash;
    UUID bank;
    UUID interest;
    LocalDate opened;

    @BeforeEach
    void cashBook() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        interest = category(tenantA, "INCOME", "BANK_INTEREST");
        opened = today().minusMonths(1).withDayOfMonth(1);
    }

    @Test
    void bankInterestOf35RaisesTheBankBalanceAndTheJournalShowsItsCategory() throws Exception {
        setOpening(chairman, bank, "20000.00", opened).andExpect(status().isOk());

        JsonNode created = body(createOtherIncome(treasurer, otherIncome("BANK", today(), "35.00", interest))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asString());

        assertThat(created.get("account").asString()).isEqualTo("BANK");
        assertThat(created.get("amount").asString()).isEqualTo("35.00");
        assertThat(created.get("bookingMonth").asString()).isEqualTo(YearMonth.from(today()).toString());
        assertThat(created.get("category").get("systemCode").asString()).isEqualTo("BANK_INTEREST");
        assertThat(created.get("reversed").asBoolean()).isFalse();
        assertThat(created.has("description")).isFalse();
        assertThat(accountOf(body(accounts(treasurer)), "BANK").get("balance").asString()).isEqualTo("20035.00");
        assertThat(journal(treasurer, bank, opened)).singleElement().satisfies(line -> {
            assertThat(line.get("kind").asString()).isEqualTo("OTHER_INCOME");
            assertThat(line.get("amount").asString()).isEqualTo("35.00");
            assertThat(line.get("categoryId").asString()).isEqualTo(interest.toString());
            assertThat(line.get("source").get("type").asString()).isEqualTo("OTHER_INCOME");
            assertThat(line.get("source").get("id").asString()).isEqualTo(id.toString());
        });
        Map<String, Object> entry = awaitAudit(id, "OTHER_INCOME_RECORDED", 1).getFirst();
        assertThat(entry).containsEntry("entity_type", "OTHER_INCOME")
                .containsEntry("entity_label", today().toString()).containsEntry("actor_member_id", treasurerId);
        assertThat((String) entry.get("changes")).contains("\"amount\"", "35.00", "\"account\"", "BANK",
                "\"category\"", interest.toString());
    }

    @Test
    void theReversalOfACashIncomeIsRefusedWhenALaterExpenseWouldLeaveTheCashNegative() throws Exception {
        setOpening(chairman, cash, "100.00", opened).andExpect(status().isOk());
        UUID income = recordOtherIncome(treasurer, otherIncome("CASH", opened.plusDays(1), "500.00",
                category(tenantA, "INCOME", "SALES")));
        UUID expense = recordExpense(treasurer, expense("CASH", opened.plusDays(2), "550.00",
                category(tenantA, "EXPENSE", "MATERIALS")));

        reverseOtherIncome(chairman, income, "Помилка").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.balance").value("50.00"))
                .andExpect(jsonPath("$.date").value(today().toString()));
        assertThat(ownerJdbc().queryForObject("select count(*) from other_income where reverses_other_income_id = ?",
                Integer.class, income)).isZero();
        assertThat(storedMovements(tenantA)).hasSize(2);

        // once the expense is reversed the cash can give the income back
        reverseExpense(chairman, expense, "Не та сума").andExpect(status().isOk());
        reverseOtherIncome(chairman, income, "Помилка").andExpect(status().isOk())
                .andExpect(jsonPath("$.reversed").value(true));
        assertThat(accountOf(body(accounts(treasurer)), "CASH").get("balance").asString()).isEqualTo("100.00");
    }

    @Test
    void theReversalOfABankIncomeIsDatedTodayHappensOnceAndMayTakeTheBankBelowZero() throws Exception {
        setOpening(chairman, bank, "0.00", opened).andExpect(status().isOk());
        UUID id = recordOtherIncome(treasurer, with(otherIncome("BANK", opened.plusDays(4), "35.00", interest),
                "description", "Відсотки за жовтень"));
        recordExpense(treasurer, expense("BANK", today(), "35.00", category(tenantA, "EXPENSE", "BANK_FEES")));

        reverseOtherIncome(chairman, id, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        JsonNode reversed = body(reverseOtherIncome(chairman, id, " Не наш рахунок ").andExpect(status().isOk()));

        assertThat(reversed.get("reversal").get("date").asString()).isEqualTo(today().toString());
        assertThat(reversed.get("reversal").get("reason").asString()).isEqualTo("Не наш рахунок");
        assertThat(reversed.get("description").asString()).isEqualTo("Відсотки за жовтень");
        JsonNode bankAccount = accountOf(body(accounts(treasurer)), "BANK");
        assertThat(bankAccount.get("balance").asString()).isEqualTo("-35.00");
        assertThat(bankAccount.get("negative").asBoolean()).isTrue();
        JsonNode lines = journal(treasurer, bank, opened);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).get("reversed").asBoolean()).isTrue();
        JsonNode reversal = lines.get(2);
        assertThat(reversal.get("kind").asString()).isEqualTo("OTHER_INCOME");
        assertThat(reversal.get("amount").asString()).isEqualTo("-35.00");
        assertThat(reversal.get("date").asString()).isEqualTo(today().toString());
        assertThat(reversal.get("reversesMovementId").asString()).isEqualTo(lines.get(0).get("id").asString());
        assertThat(reversal.get("categoryId").asString()).isEqualTo(interest.toString());

        reverseOtherIncome(chairman, id, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        UUID reversalRow = ownerJdbc().queryForObject(
                "select id from other_income where reverses_other_income_id = ?", UUID.class, id);
        reverseOtherIncome(chairman, reversalRow, "Сторно сторно").andExpect(status().isNotFound());
        reverseOtherIncome(chairman, UUID.randomUUID(), "Немає").andExpect(status().isNotFound());
        Map<String, Object> entry = awaitAudit(id, "OTHER_INCOME_REVERSED", 1).getFirst();
        assertThat((String) entry.get("changes")).contains("\"reason\"", "Не наш рахунок", "\"reversedOn\"",
                today().toString(), "\"description\"", "\"enc\"").doesNotContain("Відсотки");
    }

    @Test
    void theFieldsTheCategoryAndTheOpeningBalanceAreChecked() throws Exception {
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        UUID grants = category(tenantA, "INCOME", "GRANTS");
        ownerJdbc().update("update expense_category set archived = true where id = ?", grants);

        expectField(otherIncome("CASH", today(), "0.00", interest), "amount", "AMOUNT_NOT_POSITIVE");
        expectField(otherIncome("CASH", today(), "1000000000000.00", interest), "amount", "INVALID");
        expectField(otherIncome("CASH", today().plusDays(1), "5.00", interest), "date", "DATE_IN_FUTURE");
        expectField(otherIncome("CASH", today(), "5.00", materials), "categoryId", "INVALID");
        expectField(otherIncome("CASH", today(), "5.00", UUID.randomUUID()), "categoryId", "INVALID");
        expectField(otherIncome("CASH", today(), "5.00", grants), "categoryId", "ARCHIVED");
        expectField(with(otherIncome("CASH", today(), "5.00", interest), "description", "х".repeat(501)),
                "description", "TOO_LONG");
        Map<String, Object> noCategory = otherIncome("CASH", today(), "5.00", interest);
        noCategory.remove("categoryId");
        expectField(noCategory, "categoryId", "REQUIRED");

        createOtherIncome(treasurer, otherIncome("CASH", today(), "5.00", interest)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(chairman, cash, "0.00", opened).andExpect(status().isOk());
        createOtherIncome(treasurer, otherIncome("CASH", opened.minusDays(1), "5.00", interest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        assertThat(storedMovements(tenantA)).isEmpty();
        createOtherIncome(treasurer, otherIncome("CASH", opened, "5.00", interest)).andExpect(status().isCreated());
    }

    @Test
    void anIncomeDatedInAClosedMonthBelongsToTheEarliestOpenMonthAndKeepsItsDate() throws Exception {
        setOpening(chairman, cash, "0.00", opened).andExpect(status().isOk());
        LocalDate lastOfClosed = today().withDayOfMonth(1).minusDays(1);
        YearMonth closed = YearMonth.from(lastOfClosed);
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenantA, closed.atDay(1));
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?", tenantA, closed.atDay(1));

        createOtherIncome(treasurer, otherIncome("CASH", lastOfClosed, "12.00", interest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(lastOfClosed.toString()))
                .andExpect(jsonPath("$.bookingMonth").value(YearMonth.from(today()).toString()));

        assertThat(storedMovements(tenantA)).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("moved_on", lastOfClosed.toString())
                .containsEntry("booking_month", today().withDayOfMonth(1).toString()));
    }

    @Test
    void theDescriptionIsNeverStoredAuditedOrPublishedInTheClear() throws Exception {
        String description = "Металобрухт-Унікальний-Тест";
        setOpening(chairman, cash, "0.00", opened).andExpect(status().isOk());
        // completed publications are deleted: a copy of every row written to the outbox is kept for the check
        ownerJdbc().execute("create table other_income_outbox_copy (serialized_event text)");
        ownerJdbc().execute("""
                create function other_income_outbox_copy() returns trigger language plpgsql security definer as $$
                begin
                    insert into other_income_outbox_copy values (new.serialized_event);
                    return new;
                end;
                $$""");
        ownerJdbc().execute("create trigger other_income_outbox_copy after insert on event_publication for each row "
                + "execute function other_income_outbox_copy()");
        try {
            UUID id = recordOtherIncome(treasurer, with(otherIncome("CASH", today(), "15.00",
                    category(tenantA, "INCOME", "SALES")), "description", description));
            reverseOtherIncome(chairman, id, "Помилка").andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(description));
            awaitAudit(id, "OTHER_INCOME_REVERSED", 1);

            Map<String, Object> stored = ownerJdbc().queryForMap("select description_enc is not null as d, "
                    + "position(convert_to(?, 'UTF8') in description_enc) as pd from other_income where id = ?",
                    description, id);
            assertThat(stored).containsEntry("d", true).containsEntry("pd", 0);
            assertThat(ownerJdbc().queryForObject("select description_enc is null from other_income "
                    + "where reverses_other_income_id = ?", Boolean.class, id)).isTrue();
            assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and "
                    + "cast(changes as text) like ?", Integer.class, tenantA, "%" + description + "%")).isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from other_income_outbox_copy where "
                    + "serialized_event like ?", Integer.class, "%" + description + "%")).isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from other_income_outbox_copy where "
                    + "serialized_event like ? and serialized_event like '%OTHER_INCOME_RECORDED%'", Integer.class,
                    "%" + id + "%")).isOne();
            // the log opens it for a reader who may see members' personal data, others see it hidden
            JsonNode opened = auditChanges(chairman, id, "OTHER_INCOME_RECORDED");
            assertThat(value(opened, "description").get("newValue").asString()).isEqualTo(description);
            JsonNode hidden = auditChanges(customRole(tenantA, "AUDIT_VIEW"), id, "OTHER_INCOME_RECORDED");
            assertThat(value(hidden, "description").get("hidden").asBoolean()).isTrue();
            assertThat(value(hidden, "description").has("newValue")).isFalse();
            assertThat(value(hidden, "amount").get("newValue").asString()).isEqualTo("15.00");
        } finally {
            ownerJdbc().execute("drop trigger if exists other_income_outbox_copy on event_publication");
            ownerJdbc().execute("drop function if exists other_income_outbox_copy()");
            ownerJdbc().execute("drop table if exists other_income_outbox_copy");
        }
    }

    // ---- helpers

    private void expectField(Map<String, Object> input, String field, String code) throws Exception {
        createOtherIncome(treasurer, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItem(field)))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code")
                        .value(Matchers.hasItem(code)));
    }

    private JsonNode auditChanges(String bearer, UUID income, String action) throws Exception {
        JsonNode page = body(send(bearer, get("/api/v1/audit").param("entityType", "OTHER_INCOME")
                .param("entityId", income.toString()), null).andExpect(status().isOk()));
        for (JsonNode item : page.get("items")) {
            if (item.get("action").asString().equals(action)) {
                return item.get("changes");
            }
        }
        throw new AssertionError("No " + action + " entry");
    }

    private static JsonNode value(JsonNode changes, String field) {
        for (JsonNode change : changes) {
            if (change.get("field").asString().equals(field)) {
                return change;
            }
        }
        throw new AssertionError("No change of " + field);
    }
}
