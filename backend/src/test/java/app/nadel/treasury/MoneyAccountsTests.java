package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The money accounts (feature 018 §2.1, §2.2, §2.11; technical considerations 2.5, 2.10): both accounts listed cash
 * first, no balance until the opening balance is entered, which is entered once; the balance on a day is the opening
 * amount plus the movements dated from the opening date; member payments dated before it stay in the journal marked
 * {@code beforeOpening} and count nowhere; the journal of a period carries the balances at its two ends; a payment
 * reversal that takes the cash below zero is saved and the account is flagged negative (example 10).
 */
class MoneyAccountsTests extends TreasuryApiSupport {

    String chairman;
    UUID chairmanId;
    String treasurer;
    UUID cash;
    UUID bank;

    @BeforeEach
    void people() {
        chairman = person(tenantA, "CHAIRMAN");
        chairmanId = lastPerson;
        treasurer = person(tenantA, "TREASURER");
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
    }

    @Test
    void bothAccountsAreListedCashFirstWithoutABalanceUntilTheOpeningBalanceIsEntered() throws Exception {
        ownerJdbc().update("insert into cooperative_settings (tenant_id, iban) values (?, ?)", tenantA,
                "UA850000000000000000000123456");

        accounts(treasurer).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(cash.toString()))
                .andExpect(jsonPath("$[0].kind").value("CASH"))
                .andExpect(jsonPath("$[0].iban").doesNotExist())
                .andExpect(jsonPath("$[0].opening").doesNotExist())
                .andExpect(jsonPath("$[0].balance").doesNotExist())
                .andExpect(jsonPath("$[0].negative").value(false))
                .andExpect(jsonPath("$[0].asOf").value(today().toString()))
                .andExpect(jsonPath("$[1].id").value(bank.toString()))
                .andExpect(jsonPath("$[1].kind").value("BANK"))
                .andExpect(jsonPath("$[1].iban").value("UA850000000000000000000123456"))
                .andExpect(jsonPath("$[1].balance").doesNotExist());
    }

    @Test
    void theOpeningBalanceIsEnteredOnceAndAudited() throws Exception {
        LocalDate opened = today().minusDays(3);

        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cash.toString()))
                .andExpect(jsonPath("$.opening.amount").value("1000.00"))
                .andExpect(jsonPath("$.opening.date").value(opened.toString()))
                .andExpect(jsonPath("$.balance").value("1000.00"))
                .andExpect(jsonPath("$.negative").value(false));
        setOpening(chairman, cash, "1200.00", opened).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_ALREADY_SET"));

        assertThat(ownerJdbc().queryForMap("select opening_amount::text as amount, opening_date::text as date, "
                + "opening_set_by from money_account where id = ?", cash)).containsEntry("amount", "1000.00")
                .containsEntry("date", opened.toString()).containsEntry("opening_set_by", chairmanId);
        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("1000.00"))
                .andExpect(jsonPath("$[1].balance").doesNotExist());
        await().atMost(Duration.ofSeconds(10)).until(() -> auditEntries(cash).size() == 1);
        assertThat(auditEntries(cash)).singleElement().satisfies(entry -> {
            assertThat(entry.get("action")).isEqualTo("OPENING_BALANCE_SET");
            assertThat(entry.get("entity_type")).isEqualTo("MONEY_ACCOUNT");
            assertThat(entry.get("entity_label")).isEqualTo("CASH");
            assertThat(entry.get("actor_member_id")).isEqualTo(chairmanId);
            assertThat(entry.get("changes").toString()).contains("\"amount\"", "1000.00", opened.toString());
        });
    }

    @Test
    void anOpeningBalanceBelowZeroOrInTheFutureIsRefused() throws Exception {
        setOpening(chairman, cash, "-1.00", today()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        setOpening(chairman, cash, "1000000000000.00", today()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        setOpening(chairman, cash, "10.00", today().plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"))
                .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        setOpening(chairman, cash, "10", today()).andExpect(status().isBadRequest());

        assertThat(ownerJdbc().queryForObject("select opening_amount from money_account where id = ?", Object.class,
                cash)).isNull();
        // 0.00 is a valid opening balance
        setOpening(chairman, bank, "0.00", today()).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void paymentsBeforeTheOpeningDateStayInTheJournalButAreNotCounted() throws Exception {
        UUID plot = paymentPlot(tenantA);
        LocalDate opened = today().minusDays(5);
        pay(treasurer, plot, "CASH", "200.00", opened.minusDays(1));
        pay(treasurer, plot, "CASH", "500.00", opened.plusDays(1));

        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("1500.00"));

        JsonNode page = body(movements(treasurer, cash, opened.minusDays(1), today()).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("amount").asString()).isEqualTo("200.00");
        assertThat(page.get("items").get(0).get("beforeOpening").asBoolean()).isTrue();
        assertThat(page.get("items").get(1).get("amount").asString()).isEqualTo("500.00");
        assertThat(page.get("items").get(1).get("beforeOpening").asBoolean()).isFalse();
        // the period starts before the opening date: no balance is known at its start; at its end the 200.00 is out
        assertThat(page.has("openingBalance")).isFalse();
        assertThat(page.get("closingBalance").asString()).isEqualTo("1500.00");
        accounts(treasurer, opened.minusDays(1)).andExpect(jsonPath("$[0].balance").value("1000.00"));
        accounts(treasurer, opened.minusDays(2)).andExpect(jsonPath("$[0].balance").doesNotExist());
    }

    @Test
    void theJournalOfAPeriodCarriesItsLinesRefsAndTheBalancesAtBothEnds() throws Exception {
        UUID plot = paymentPlot(tenantA);
        LocalDate opened = today().minusDays(10);
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        pay(treasurer, plot, "CASH", "500.00", opened.plusDays(2));
        UUID later = pay(treasurer, plot, "CASH", "300.00", opened.plusDays(5));
        UUID bankPayment = UUID.fromString(body(save(treasurer, with(payment(plot, "BANK", "70.00", opened),
                "bankReference", "ПД-15")).andExpect(status().isCreated())).get("payments").get(0).get("id")
                .asString());
        reverse(chairman, later, "Помилкова ділянка");
        Long receipt = ownerJdbc().queryForObject("select receipt_number from payment where id = ?", Long.class, later);

        // from the day after the first payment to today: 1000.00 + 500.00 at the start, the 300.00 and its reversal
        JsonNode page = body(movements(treasurer, cash, opened.plusDays(3), today()).andExpect(status().isOk()));

        assertThat(page.get("accountId").asString()).isEqualTo(cash.toString());
        assertThat(page.get("kind").asString()).isEqualTo("CASH");
        assertThat(page.get("openingBalance").asString()).isEqualTo("1500.00");
        assertThat(page.get("closingBalance").asString()).isEqualTo("1500.00");
        assertThat(page.get("negative").asBoolean()).isFalse();
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        JsonNode payment = page.get("items").get(0);
        JsonNode reversal = page.get("items").get(1);
        assertThat(payment.get("date").asString()).isEqualTo(opened.plusDays(5).toString());
        assertThat(payment.get("kind").asString()).isEqualTo("MEMBER_PAYMENT");
        assertThat(payment.get("amount").asString()).isEqualTo("300.00");
        assertThat(payment.get("source").get("type").asString()).isEqualTo("PAYMENT");
        assertThat(payment.get("source").get("id").asString()).isEqualTo(later.toString());
        assertThat(payment.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(payment.get("receiptNumber").asLong()).isEqualTo(receipt);
        assertThat(payment.get("reversed").asBoolean()).isTrue();
        assertThat(payment.get("createdByName").asString()).startsWith("Скарбник Тест");
        assertThat(reversal.get("date").asString()).isEqualTo(today().toString());
        assertThat(reversal.get("amount").asString()).isEqualTo("-300.00");
        assertThat(reversal.get("reversesMovementId").asString()).isEqualTo(payment.get("id").asString());
        assertThat(reversal.get("reason").asString()).isEqualTo("Помилкова ділянка");
        assertThat(reversal.get("receiptNumber").asLong()).as("the reversal shows its payment's receipt")
                .isEqualTo(receipt);
        assertThat(reversal.get("reversed").asBoolean()).isFalse();
        assertThat(reversal.get("bookingMonth").asString()).isEqualTo(today().toString().substring(0, 7));
        // the bank journal: the bank document number of a bank payment
        JsonNode bankPage = body(movements(treasurer, bank, opened, today()).andExpect(status().isOk()));
        assertThat(bankPage.get("items")).singleElement().satisfies(line -> {
            assertThat(line.get("source").get("id").asString()).isEqualTo(bankPayment.toString());
            assertThat(line.get("documentNumber").asString()).isEqualTo("ПД-15");
            assertThat(line.has("receiptNumber")).isFalse();
            assertThat(line.get("beforeOpening").asBoolean()).isFalse();
        });
        assertThat(bankPage.has("closingBalance")).as("the bank has no opening balance").isFalse();
        // paging: the first line alone, the balances still over the whole period
        JsonNode first = body(send(treasurer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(ACCOUNTS + "/" + cash + "/movements").param("from", opened.toString())
                .param("to", today().toString()).param("page", "0").param("size", "1"), null)
                .andExpect(status().isOk()));
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("totalItems").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("openingBalance").asString()).isEqualTo("1000.00");
        assertThat(first.get("closingBalance").asString()).isEqualTo("1500.00");
    }

    @Test
    void aPaymentReversalThatTakesTheCashBelowZeroIsSavedAndTheAccountIsFlaggedNegative() throws Exception {
        UUID plot = paymentPlot(tenantA);
        LocalDate opened = today().minusDays(2);
        // received before the opening date, so it is not in the opening's 0.00; its reversal today is
        UUID payment = pay(treasurer, plot, "CASH", "300.00", opened.minusDays(1));
        setOpening(chairman, cash, "0.00", opened).andExpect(status().isOk());

        reverse(chairman, payment, "Платіж повернуто");

        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("-300.00"))
                .andExpect(jsonPath("$[0].negative").value(true));
        movements(treasurer, cash, opened, today()).andExpect(jsonPath("$.closingBalance").value("-300.00"))
                .andExpect(jsonPath("$.negative").value(true));
    }

    @Test
    void wrongPeriodsAndDaysAreRefusedAndAnUnknownAccountIsNotFound() throws Exception {
        movements(treasurer, cash, today(), today().minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        accounts(treasurer, today().plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("asOf"));
        movements(treasurer, UUID.randomUUID(), today(), today()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        setOpening(chairman, UUID.randomUUID(), "1.00", today()).andExpect(status().isNotFound());
        // without from and to: the current month up to today
        send(treasurer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(ACCOUNTS + "/" + cash + "/movements"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today().withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.to").value(today().toString()));
    }

    // ---- helpers

    private List<Map<String, Object>> auditEntries(UUID entity) {
        return new ArrayList<>(ownerJdbc().queryForList("select action, entity_type, entity_label, actor_member_id, "
                + "changes from audit_entry where entity_id = ?", entity));
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }
}
