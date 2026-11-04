package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Cash counts (feature 018 §2.6, §2.8, §2.11, §2.14; worked example 2.17 #6): the server computes the book balance on
 * the count's day and the difference; a matching count is «Збігається»; a difference needs an explanation and shows on
 * the cash card as unresolved until the chairman books it, once, dated the count's day (a shortage never takes the cash
 * negative on a later day); the save computes the book balance again under the lock and keeps it for good; a booked
 * difference line is reversed directly, once, dated today, and the count stays booked.
 */
class CashCountsTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    UUID treasurerId;
    UUID chairmanId;
    UUID cash;
    UUID bank;
    LocalDate opened;

    @BeforeEach
    void cashBook() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        chairmanId = lastPerson;
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        opened = today().minusMonths(1).withDayOfMonth(1);
    }

    /** FS 2.17 rows 1 to 3 without the payment: cash 1 000,00, an expense of 300,00 on the 6th, a handover on the 7th. */
    private void cash200() throws Exception {
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "20000.00", opened).andExpect(status().isOk());
        recordExpense(treasurer, expense("CASH", opened.plusDays(5), "300.00",
                category(tenantA, "EXPENSE", "MATERIALS")));
        recordHandover(treasurer, handover(opened.plusDays(6), "500.00", "КВ-1"));
    }

    private String cashBalance() throws Exception {
        return accountOf(body(accounts(treasurer)), "CASH").get("balance").asString();
    }

    @Test
    void example6ACountOf180Against200IsUnresolvedUntilTheChairmanBooksTheShortageOnTheCountDay() throws Exception {
        cash200();
        LocalDate tenth = opened.plusDays(9);
        Instant countedAt = kyiv(tenth, 14);

        JsonNode saved = body(enterCount(treasurer, count(countedAt, "180.00", " Помилка при видачі решти "))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(saved.get("id").asString());

        assertThat(saved.get("preview").asBoolean()).isFalse();
        assertThat(saved.get("countedOn").asString()).isEqualTo(tenth.toString());
        assertThat(saved.get("bookBalance").asString()).isEqualTo("200.00");
        assertThat(saved.get("counted").asString()).isEqualTo("180.00");
        assertThat(saved.get("difference").asString()).isEqualTo("-20.00");
        assertThat(saved.get("status").asString()).isEqualTo("UNRESOLVED");
        assertThat(saved.get("explanation").asString()).isEqualTo("Помилка при видачі решти");
        assertThat(saved.get("createdByName").asString()).startsWith("Скарбник");
        assertThat(saved.has("booking")).isFalse();
        assertThat(saved.has("previousCount")).isFalse();
        // no previous count: every counted line since the opening date
        assertThat(saved.get("movementsSinceTotal").asLong()).isEqualTo(2);
        assertThat(saved.get("movementsSince")).extracting(line -> line.get("kind").asString())
                .containsExactly("EXPENSE", "HANDOVER_OUT");
        JsonNode card = accountOf(body(accounts(treasurer)), "CASH");
        assertThat(card.get("unresolvedDifference").asString()).isEqualTo("-20.00");
        assertThat(card.get("lastCount").get("id").asString()).isEqualTo(id.toString());
        assertThat(card.get("lastCount").get("status").asString()).isEqualTo("UNRESOLVED");
        assertThat(card.get("balance").asString()).isEqualTo("200.00");

        bookDifference(chairman, id, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        JsonNode booked = body(bookDifference(chairman, id, " Нестача за актом ").andExpect(status().isOk()));

        assertThat(booked.get("status").asString()).isEqualTo("BOOKED");
        assertThat(booked.get("bookBalance").asString()).as("the count keeps its book balance").isEqualTo("200.00");
        JsonNode booking = booked.get("booking");
        assertThat(booking.get("kind").asString()).isEqualTo("SHORTAGE");
        assertThat(booking.get("amount").asString()).isEqualTo("-20.00");
        assertThat(booking.get("date").asString()).isEqualTo(tenth.toString());
        assertThat(booking.get("bookingMonth").asString()).isEqualTo(YearMonth.from(tenth).toString());
        assertThat(booking.get("reason").asString()).isEqualTo("Нестача за актом");
        assertThat(booking.get("reversed").asBoolean()).isFalse();
        assertThat(cashBalance()).isEqualTo("180.00");
        card = accountOf(body(accounts(treasurer)), "CASH");
        assertThat(card.has("unresolvedDifference")).isFalse();
        assertThat(card.get("lastCount").get("status").asString()).isEqualTo("BOOKED");
        JsonNode line = journal(treasurer, cash, opened).get(2);
        assertThat(line.get("kind").asString()).isEqualTo("COUNT_DIFFERENCE");
        assertThat(line.get("amount").asString()).isEqualTo("-20.00");
        assertThat(line.get("date").asString()).isEqualTo(tenth.toString());
        assertThat(line.get("source").get("type").asString()).isEqualTo("CASH_COUNT");
        assertThat(line.get("source").get("id").asString()).isEqualTo(id.toString());
        assertThat(line.get("id").asString()).isEqualTo(booking.get("movementId").asString());

        bookDifference(chairman, id, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIFFERENCE_ALREADY_BOOKED"));
        assertThat(journal(treasurer, cash, opened)).hasSize(3);
        assertThat(cashBalance()).isEqualTo("180.00");

        Map<String, Object> counted = awaitAudit(id, "CASH_COUNTED", 1).getFirst();
        assertThat(counted).containsEntry("entity_type", "CASH_COUNT")
                .containsEntry("entity_label", countedAt.toString()).containsEntry("actor_member_id", treasurerId);
        assertThat((String) counted.get("changes")).contains("\"counted\"", "180.00", "\"bookBalance\"", "200.00",
                "\"difference\"", "-20.00", "\"explanation\"", "Помилка при видачі решти");
        Map<String, Object> bookedEntry = awaitAudit(id, "COUNT_DIFFERENCE_BOOKED", 1).getFirst();
        assertThat(bookedEntry).containsEntry("actor_member_id", chairmanId);
        assertThat((String) bookedEntry.get("changes")).contains("\"amount\"", "-20.00", "\"date\"", tenth.toString(),
                "\"reason\"", "Нестача за актом");
    }

    @Test
    void aCountThatMatchesIsSavedAsMatchesWithoutAnExplanationAndHasNothingToBook() throws Exception {
        cash200();

        JsonNode saved = body(enterCount(treasurer, count(kyiv(opened.plusDays(9), 9), "200.00", null))
                .andExpect(status().isCreated()));

        assertThat(saved.get("difference").asString()).isEqualTo("0.00");
        assertThat(saved.get("status").asString()).isEqualTo("MATCHES");
        assertThat(saved.has("explanation")).isFalse();
        JsonNode card = accountOf(body(accounts(treasurer)), "CASH");
        assertThat(card.get("lastCount").get("status").asString()).isEqualTo("MATCHES");
        assertThat(card.has("unresolvedDifference")).isFalse();
        bookDifference(chairman, UUID.fromString(saved.get("id").asString()), "Нічого")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_DIFFERENCE"));
        assertThat(journal(treasurer, cash, opened)).hasSize(2);
    }

    @Test
    void aDifferenceNeedsAnExplanationOnTheSaveButNotInThePreviewAndThePreviewStoresNothing() throws Exception {
        cash200();
        Map<String, Object> count = count(kyiv(opened.plusDays(9), 10), "180.00", null);

        enterCount(treasurer, count).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("explanation"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        enterCount(treasurer, with(count, "explanation", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        JsonNode preview = body(previewCount(treasurer, count).andExpect(status().isOk()));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.has("id")).isFalse();
        assertThat(preview.get("bookBalance").asString()).isEqualTo("200.00");
        assertThat(preview.get("difference").asString()).isEqualTo("-20.00");
        assertThat(preview.get("status").asString()).isEqualTo("UNRESOLVED");
        assertThat(preview.get("movementsSince")).hasSize(2);
        assertThat(ownerJdbc().queryForObject("select count(*) from cash_count where tenant_id = ?", Integer.class,
                tenantA)).isZero();
        assertThat(accountOf(body(accounts(treasurer)), "CASH").has("lastCount")).isFalse();
    }

    @Test
    void theSaveComputesTheBookBalanceAgainSoALineEnteredAfterThePreviewCounts() throws Exception {
        cash200();
        Map<String, Object> count = count(kyiv(opened.plusDays(9), 11), "180.00", null);
        assertThat(body(previewCount(treasurer, count).andExpect(status().isOk())).get("bookBalance").asString())
                .isEqualTo("200.00");

        // between the preview and the save another treasurer records a cash expense of 20,00 on the count's day
        recordExpense(treasurer, expense("CASH", opened.plusDays(9), "20.00",
                category(tenantA, "EXPENSE", "MATERIALS")));
        JsonNode saved = body(enterCount(treasurer, count).andExpect(status().isCreated()));

        assertThat(saved.get("bookBalance").asString()).isEqualTo("180.00");
        assertThat(saved.get("difference").asString()).isEqualTo("0.00");
        assertThat(saved.get("status").asString()).isEqualTo("MATCHES");
        assertThat(ownerJdbc().queryForObject("select book_balance::text from cash_count where tenant_id = ?",
                String.class, tenantA)).isEqualTo("180.00");
    }

    @Test
    void aSavedCountKeepsItsBookBalanceAfterABackDatedEntryWhichTheNextCountListsAsANewLine() throws Exception {
        cash200();
        UUID first = saveCount(treasurer, count(kyiv(opened.plusDays(9), 9), "200.00", null));

        // entered after the count, dated the day before it
        recordOtherIncome(treasurer, otherIncome("CASH", opened.plusDays(8), "50.00",
                category(tenantA, "INCOME", "SALES")));

        JsonNode card = body(countCard(treasurer, first).andExpect(status().isOk()));
        assertThat(card.get("bookBalance").asString()).isEqualTo("200.00");
        assertThat(card.get("difference").asString()).isEqualTo("0.00");
        assertThat(card.get("status").asString()).isEqualTo("MATCHES");
        assertThat(card.get("movementsSince")).as("as it was when saved").hasSize(2);
        assertThat(cashBalance()).isEqualTo("250.00");

        JsonNode next = body(previewCount(treasurer, count(kyiv(opened.plusDays(10), 9), "250.00", null))
                .andExpect(status().isOk()));
        assertThat(next.get("bookBalance").asString()).isEqualTo("250.00");
        assertThat(next.get("previousCount").get("id").asString()).isEqualTo(first.toString());
        assertThat(next.get("previousCount").get("bookBalance").asString()).isEqualTo("200.00");
        // previous book 200,00 plus the lines since it is this book balance
        assertThat(next.get("movementsSince")).singleElement().satisfies(line -> {
            assertThat(line.get("kind").asString()).isEqualTo("OTHER_INCOME");
            assertThat(line.get("amount").asString()).isEqualTo("50.00");
            assertThat(line.get("date").asString()).isEqualTo(opened.plusDays(8).toString());
        });
    }

    @Test
    void aSurplusIsBookedAsAReceiptAndItsLineIsReversedOnceTodayWhileTheCountStaysBooked() throws Exception {
        cash200();
        UUID id = saveCount(treasurer, count(kyiv(opened.plusDays(9), 15), "250.00", "Знайдено в сейфі"));
        JsonNode booked = body(bookDifference(chairman, id, "Надлишок за актом").andExpect(status().isOk()));
        assertThat(booked.get("booking").get("kind").asString()).isEqualTo("SURPLUS");
        assertThat(booked.get("booking").get("amount").asString()).isEqualTo("50.00");
        assertThat(cashBalance()).isEqualTo("250.00");
        UUID line = UUID.fromString(booked.get("booking").get("movementId").asString());

        reverseMovement(chairman, cash, line, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        reverseMovement(chairman, bank, line, "Не той рахунок").andExpect(status().isNotFound());
        JsonNode reversal = body(reverseMovement(chairman, cash, line, " Гроші не з каси ")
                .andExpect(status().isOk()));

        assertThat(reversal.get("kind").asString()).isEqualTo("COUNT_DIFFERENCE");
        assertThat(reversal.get("amount").asString()).isEqualTo("-50.00");
        assertThat(reversal.get("date").asString()).isEqualTo(today().toString());
        assertThat(reversal.get("bookingMonth").asString()).isEqualTo(YearMonth.from(today()).toString());
        assertThat(reversal.get("reversesMovementId").asString()).isEqualTo(line.toString());
        assertThat(reversal.get("reason").asString()).isEqualTo("Гроші не з каси");
        assertThat(reversal.get("source").get("type").asString()).isEqualTo("MOVEMENT");
        assertThat(reversal.get("source").get("id").asString()).isEqualTo(line.toString());
        assertThat(cashBalance()).isEqualTo("200.00");
        JsonNode card = body(countCard(treasurer, id).andExpect(status().isOk()));
        assertThat(card.get("status").asString()).isEqualTo("BOOKED");
        assertThat(card.get("booking").get("reversed").asBoolean()).isTrue();
        assertThat(card.get("booking").get("reversal").get("date").asString()).isEqualTo(today().toString());
        assertThat(card.get("booking").get("reversal").get("reason").asString()).isEqualTo("Гроші не з каси");
        assertThat(accountOf(body(accounts(treasurer)), "CASH").has("unresolvedDifference")).isFalse();

        reverseMovement(chairman, cash, line, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        reverseMovement(chairman, cash, UUID.fromString(reversal.get("id").asString()), "Сторно сторно")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOVEMENT_NOT_REVERSIBLE"));
        UUID expenseLine = UUID.fromString(journal(treasurer, cash, opened).get(0).get("id").asString());
        reverseMovement(chairman, cash, expenseLine, "Не так").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVEMENT_NOT_REVERSIBLE"));
        bookDifference(chairman, id, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIFFERENCE_ALREADY_BOOKED"));
        reverseMovement(chairman, cash, UUID.randomUUID(), "Немає").andExpect(status().isNotFound());

        Map<String, Object> entry = awaitAudit(id, "COUNT_DIFFERENCE_REVERSED", 1).getFirst();
        assertThat((String) entry.get("changes")).contains("\"amount\"", "50.00", "\"reversedOn\"",
                today().toString(), "\"reason\"", "Гроші не з каси");
    }

    @Test
    void aShortageIsRefusedWhenALaterDayWouldGoNegative() throws Exception {
        setOpening(chairman, cash, "200.00", opened).andExpect(status().isOk());
        UUID id = saveCount(treasurer, count(kyiv(opened.plusDays(4), 12), "150.00", "Не вистачає"));
        // later the cash is spent down to 20,00
        recordExpense(treasurer, expense("CASH", opened.plusDays(6), "180.00",
                category(tenantA, "EXPENSE", "MATERIALS")));

        bookDifference(chairman, id, "Нестача").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.balance").value("20.00"))
                .andExpect(jsonPath("$.date").value(opened.plusDays(6).toString()));
        assertThat(body(countCard(treasurer, id)).get("status").asString()).isEqualTo("UNRESOLVED");
        assertThat(journal(treasurer, cash, opened)).hasSize(1);
    }

    @Test
    void refusalsWithoutAnOpeningBalanceBeforeItInTheFutureAndForANegativeCount() throws Exception {
        enterCount(treasurer, count(Instant.now(), "10.00", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        previewCount(treasurer, count(Instant.now(), "10.00", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(chairman, cash, "100.00", opened).andExpect(status().isOk());

        enterCount(treasurer, count(kyiv(opened.minusDays(1), 12), "10.00", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("countedAt"));
        enterCount(treasurer, count(Instant.now().plus(1, ChronoUnit.HOURS), "100.00", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("countedAt"))
                .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        enterCount(treasurer, count(Instant.now(), "-1.00", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("counted"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        enterCount(treasurer, count(Instant.now(), "90.00", "я".repeat(501))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("explanation"));
        // a count now, of the whole cash, with a few seconds of clock difference, is fine
        JsonNode now = body(enterCount(treasurer, count(Instant.now().plusSeconds(30), "100.00", null))
                .andExpect(status().isCreated()));
        assertThat(now.get("status").asString()).isEqualTo("MATCHES");
        assertThat(ownerJdbc().queryForObject("select count(*) from cash_count where tenant_id = ?", Integer.class,
                tenantA)).isEqualTo(1);
    }

    @Test
    void theListIsNewestFirstAndAnUnknownCountIsNotFound() throws Exception {
        cash200();
        UUID older = saveCount(treasurer, count(kyiv(opened.plusDays(7), 9), "200.00", null));
        UUID newer = saveCount(treasurer, count(kyiv(opened.plusDays(9), 9), "190.00", "Решта"));
        // a count entered later for an earlier moment sits by its moment
        UUID backDated = saveCount(treasurer, count(kyiv(opened.plusDays(8), 9), "200.00", null));

        JsonNode page = body(counts(treasurer).andExpect(status().isOk()));
        assertThat(page.get("totalItems").asLong()).isEqualTo(3);
        assertThat(page.get("items")).extracting(item -> item.get("id").asString())
                .containsExactly(newer.toString(), backDated.toString(), older.toString());
        assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("UNRESOLVED");
        assertThat(body(countCard(treasurer, newer)).get("previousCount").get("id").asString())
                .isEqualTo(backDated.toString());
        assertThat(accountOf(body(accounts(treasurer)), "CASH").get("lastCount").get("id").asString())
                .isEqualTo(newer.toString());
        countCard(treasurer, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        bookDifference(chairman, UUID.randomUUID(), "Немає").andExpect(status().isNotFound());
        assertThat(List.of(older, newer, backDated)).doesNotHaveDuplicates();
    }
}
