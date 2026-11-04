package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Cash book, expenses and reconciliation" (functional-spec.md of 018) through the
 * public HTTP API, for what the per-slice tests do not reach on their own: the connected story of the worked examples
 * (FS 2.17 #1 to #12) on ONE cooperative with real people (chairman, treasurer, a member who owns the plot), the rights
 * of FS 2.13 for every endpoint family including a custom role, and the isolation of a second cooperative that holds
 * every permission. The per-case rules stay in the tests of the {@code treasury}, {@code documents}, {@code ledger} and
 * {@code reports} modules; {@code acceptance-coverage.md} of the spec maps every criterion to its tests.
 * <p>
 * The dates are relative to today (the 1st of the previous month is the opening date of both accounts, so a later entry
 * with an earlier date is real and the story never depends on a fixed month). The class sits in the package of the slice
 * tests because their fixtures are package-private.
 * <pre>
 * @layer: integration
 * @spec: 018-cash-book-expenses-reconciliation
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(180)
class TreasuryAcceptanceTests extends TreasuryReportsSupport {

    private String chairman;
    private String treasurer;
    private String member;
    private UUID memberId;
    private UUID cash;
    private UUID bank;
    private UUID ownPlot;
    private UUID otherPlot;
    private LocalDate opened;
    private YearMonth month;

    @BeforeEach
    void cooperativeWithPeople() {
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, "
                + "iban) values (?, 'Садівниче товариство «Приймання»', 'с. Тестове, вул. Садова, 1', "
                + "'АТ «Тестбанк»', 'UA850000000000000000000123456')", tenantA);
        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", tenantA);
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        member = person(tenantA, "MEMBER");
        memberId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        ownPlot = paymentPlot(tenantA);
        otherPlot = paymentPlot(tenantA);
        own(tenantA, memberId, ownPlot);
        month = YearMonth.from(today()).minusMonths(1);
        opened = month.atDay(1);
    }

    // ---- FS 2.1 to 2.8, 2.10, 2.11, 2.12, 2.17 as one story

    /**
     * FS 2.17 #1 to #12 in one cooperative: opening balances once and only by the chairman, a member's cash payment,
     * a cash expense with a photo, the handover, the two refusals, the count with its booked shortage, the October
     * bank reconciliation with the bank fee, the reopening by a back-dated bank payment, a refused statement, the
     * reversal, the identity of the income and expense report and the reconciliation act of the member.
     */
    @Test
    void theWholeCashBookStoryOfTheWorkedExamplesOnOneCooperative() throws Exception {
        // 2.1: no opening balance, no balance; entries are refused; the treasurer cannot enter one; the chairman does it once
        JsonNode before = body(accounts(treasurer).andExpect(status().isOk()));
        assertThat(accountOf(before, "CASH").has("balance")).isFalse();
        assertThat(accountOf(before, "BANK").has("balance")).isFalse();
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        createExpense(treasurer, expense("BANK", today(), "5.00", materials)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(treasurer, cash, "1000.00", opened).andExpect(status().isForbidden());
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "20000.00", opened).andExpect(status().isOk());
        setOpening(chairman, cash, "999.00", opened).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_ALREADY_SET"));
        assertThat(balance("CASH")).isEqualTo("1000.00");

        // #1: a cash payment of 500,00 of the member's plot (a charge so that the act has a line)
        charge(tenantA, ownPlot, "MEMBERSHIP", "75.00", opened.plusDays(1));
        charge(tenantA, otherPlot, "MEMBERSHIP", "90.00", opened.plusDays(1));
        pay(treasurer, ownPlot, "CASH", "500.00", opened.plusDays(4));
        assertThat(balance("CASH")).isEqualTo("1500.00");
        assertThat(journal(treasurer, cash, opened)).extracting(l -> l.get("kind").asString())
                .contains("MEMBER_PAYMENT");

        // #2: a cash expense of 300,00 «Матеріали» with a camera photo: listed, cash down by 300,00, no GPS after download
        UUID spent = recordExpense(treasurer, expense("CASH", opened.plusDays(5), "300.00", materials),
                file("receipt.jpg", "image/jpeg", TestImages.cameraJpeg(false)));
        assertThat(balance("CASH")).isEqualTo("1200.00");
        byte[] photo = download(treasurer, spent, 1).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(TestImages.contains(photo, "GPS")).isFalse();
        assertThat(body(expenseCard(treasurer, spent).andExpect(status().isOk())).get("attachments")).hasSize(1);

        // #3: the handover with the document number reaches both journals
        recordHandover(treasurer, handover(opened.plusDays(6), "1000.00", "КВ-123"));
        assertThat(balance("CASH")).isEqualTo("200.00");
        assertThat(balance("BANK")).isEqualTo("21000.00");
        for (UUID account : List.of(cash, bank)) {
            assertThat(journal(treasurer, account, opened)).anySatisfy(
                    line -> assertThat(line.path("documentNumber").asString()).isEqualTo("КВ-123"));
        }

        // #4 and #5: the refusals name the balance and the day; nothing is saved
        createExpense(treasurer, expense("CASH", opened.plusDays(6), "250.00", materials))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.date").value(opened.plusDays(6).toString()));
        createExpense(treasurer, expense("CASH", opened.plusDays(5), "400.00", materials))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.date").value(opened.plusDays(6).toString()));
        assertThat(balance("CASH")).isEqualTo("200.00");

        // #6: the count of 180,00 against 200,00 needs an explanation, stays unresolved, only the chairman books it
        Map<String, Object> noReason = count(kyiv(opened.plusDays(9), 12), "180.00", null);
        enterCount(treasurer, noReason).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("explanation"));
        UUID counted = saveCount(treasurer, count(kyiv(opened.plusDays(9), 12), "180.00", "Помилка при видачі решти"));
        JsonNode cashCard = accountOf(body(accounts(treasurer).andExpect(status().isOk())), "CASH");
        assertThat(cashCard.get("unresolvedDifference").asString()).isEqualTo("-20.00");
        bookDifference(treasurer, counted, "Нестача").andExpect(status().isForbidden());
        bookDifference(chairman, counted, "Нестача за актом").andExpect(status().isOk());
        assertThat(balance("CASH")).isEqualTo("180.00");
        assertThat(accountOf(body(accounts(treasurer).andExpect(status().isOk())), "CASH")
                .has("unresolvedDifference")).isFalse();
        String countAct = pdfText(countAct(treasurer, counted).andExpect(status().isOk()));
        assertThat(countAct).contains("200,00", "180,00", "20,00");

        // #9: a statement that does not add up is refused and nothing is stored
        enterStatement(treasurer, month, "100.00", "50.00", "20.00", "200.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_INCONSISTENT"));

        // #7: the bank month of the statement 20 000,00 / 3 000,00 / 150,00 / 22 850,00
        pay(treasurer, otherPlot, "BANK", "1200.00", opened.plusDays(3));
        pay(treasurer, otherPlot, "BANK", "800.00", opened.plusDays(13));
        enterStatement(treasurer, month, "20000.00", "3000.00", "150.00", "22850.00").andExpect(status().isOk());
        JsonNode differing = body(reconciliation(treasurer, month).andExpect(status().isOk()));
        assertThat(differing.get("balanced").asBoolean()).isFalse();
        assertThat(differing.get("differences").get("outgoing").asString()).isEqualTo("150.00");
        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));
        recordExpense(treasurer, expense("BANK", month.atEndOfMonth(), "150.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));
        assertThat(body(reconciliation(treasurer, month).andExpect(status().isOk())).get("balanced").asBoolean())
                .isTrue();
        assertThat(body(confirmMonth(treasurer, month).andExpect(status().isOk())).get("status").asString())
                .isEqualTo("RECONCILED");
        assertThat(monthStatus()).isEqualTo("RECONCILED");

        // #8: a bank payment dated in the reconciled month and entered today reopens it
        pay(treasurer, otherPlot, "BANK", "400.00", opened.plusDays(19));
        assertThat(monthStatus()).isEqualTo("NEEDS_RECHECK");

        // 2.8: the reversal needs a reason, restores the cash, and is made once
        String cashBefore = balance("CASH");
        reverseExpense(chairman, spent, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        reverseExpense(chairman, spent, "Невірна сума").andExpect(status().isOk());
        assertThat(new java.math.BigDecimal(balance("CASH")))
                .isEqualByComparingTo(new java.math.BigDecimal(cashBefore).add(new java.math.BigDecimal("300.00")));
        reverseExpense(chairman, spent, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));

        // #12: income - expenses + differences = the change of cash + bank over the whole period
        JsonNode report = preview(chairman, "income-expense", opened, today());
        JsonNode main = sheet(report, "main");
        JsonNode balances = sheet(report, "balances");
        assertThat(cell(main, total(main, "result"), "amount"))
                .isEqualTo(cell(balances, total(balances, "all"), "change"));
        assertThat(cell(balances, balances.get("rows").get(0), "end")).isEqualTo(balance("CASH"));
        assertThat(cell(balances, balances.get("rows").get(1), "end")).isEqualTo(balance("BANK"));

        // #11 and 2.10: the member downloads the act of their own plot; another plot and a future end are refused
        String act = pdfText(act(member, ownPlot, opened.plusDays(1), today()).andExpect(status().isOk()));
        assertThat(act).contains(plotNumber(ownPlot), "Нараховано", "Сплачено", "Від кооперативу", "Член кооперативу");
        act(member, otherPlot, opened.plusDays(1), today()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        act(treasurer, ownPlot, opened.plusDays(1), today().plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        assertThat(pdfText(act(treasurer, otherPlot, opened.plusDays(1), today()).andExpect(status().isOk())))
                .contains(plotNumber(otherPlot));
    }

    /**
     * FS 2.7 #7 (owner decision on PR #66): «Звірено» needs only the closing difference to be 0,00. A member's bank
     * payment entered on the month's last day that the bank books on the 1st of the next month: the chairman books a
     * bank difference in the first month and reverses it in the next; each month then closes with its incoming and
     * outgoing off by the same amount (a warning on the screen, not a block), while a closing difference still blocks.
     */
    @Test
    void aMonthIsReconciledWhenOnlyItsClosingAgreesAndTheIncomingAndOutgoingDifferencesAreAWarning()
            throws Exception {
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "20000.00", opened).andExpect(status().isOk());
        pay(treasurer, otherPlot, "BANK", "500.00", month.atEndOfMonth());

        // the bank's statement of the month does not have the payment yet: the closing differs and blocks
        JsonNode first = body(enterStatement(treasurer, month, "20000.00", "0.00", "0.00", "20000.00")
                .andExpect(status().isOk()));
        assertThat(first.get("differences").get("incoming").asString()).isEqualTo("-500.00");
        assertThat(first.get("differences").get("closing").asString()).isEqualTo("-500.00");
        assertThat(first.get("balanced").asBoolean()).isFalse();
        confirmMonth(treasurer, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));

        // the chairman books the timing as a bank difference: the closing agrees, in and out each differ by 500,00
        JsonNode booked = body(bookBankDifference(chairman, month, "-500.00", "Банк зарахував платіж 1-го числа")
                .andExpect(status().isOk()));
        assertThat(booked.get("differences").get("incoming").asString()).isEqualTo("-500.00");
        assertThat(booked.get("differences").get("outgoing").asString()).isEqualTo("-500.00");
        assertThat(booked.get("differences").get("closing").asString()).isEqualTo("0.00");
        assertThat(booked.get("balanced").asBoolean()).isTrue();
        assertThat(body(confirmMonth(treasurer, month).andExpect(status().isOk())).get("status").asString())
                .isEqualTo("RECONCILED");
        assertThat(monthStatus()).isEqualTo("RECONCILED");

        // the next month's statement has the payment; the reversal of the difference, dated today, closes it too
        YearMonth current = YearMonth.from(today());
        enterStatement(treasurer, current, "20000.00", "500.00", "0.00", "20500.00").andExpect(status().isOk());
        UUID difference = null;
        for (JsonNode line : booked.get("movements")) {
            if (line.get("kind").asString().equals("BANK_DIFFERENCE")) {
                difference = UUID.fromString(line.get("id").asString());
            }
        }
        assertThat(difference).isNotNull();
        reverseMovement(chairman, bank, difference, "Платіж зараховано в наступному місяці")
                .andExpect(status().isOk());
        JsonNode next = body(reconciliation(treasurer, current).andExpect(status().isOk()));
        assertThat(next.get("differences").get("incoming").asString()).isEqualTo("500.00");
        assertThat(next.get("differences").get("outgoing").asString()).isEqualTo("500.00");
        assertThat(next.get("differences").get("closing").asString()).isEqualTo("0.00");
        assertThat(next.get("balanced").asBoolean()).isTrue();
        assertThat(body(confirmMonth(treasurer, current).andExpect(status().isOk())).get("status").asString())
                .isEqualTo("RECONCILED");
        assertThat(balance("BANK")).isEqualTo("20500.00");
        assertThat(monthStatus()).as("the reversal is dated in the next month").isEqualTo("RECONCILED");
    }

    // ---- FS 2.13 rights, every endpoint family

    /** FS 2.13 #1: the roles screen lists the three rights; the treasurer has the first two, the chairman all three. */
    @Test
    void theRolesScreenListsTheThreeRightsTheTreasurerHasTwoAndTheChairmanAllThree() throws Exception {
        JsonNode roles = body(send(chairman, get("/api/v1/roles"), null).andExpect(status().isOk()));
        assertThat(permissionsOf(roles, "CHAIRMAN")).contains("CASHBOOK_VIEW", "CASHBOOK_ENTER", "CASHBOOK_APPROVE");
        assertThat(permissionsOf(roles, "TREASURER")).contains("CASHBOOK_VIEW", "CASHBOOK_ENTER")
                .doesNotContain("CASHBOOK_APPROVE");
        assertThat(permissionsOf(roles, "MEMBER")).noneMatch(p -> p.startsWith("CASHBOOK_"));
        JsonNode catalog = body(send(chairman, get("/api/v1/permissions"), null).andExpect(status().isOk()));
        List<String> codes = new ArrayList<>();
        catalog.forEach(p -> codes.add(p.get("code").asString()));
        assertThat(codes).contains("CASHBOOK_VIEW", "CASHBOOK_ENTER", "CASHBOOK_APPROVE");
    }

    /**
     * FS 2.13 #2, #3 and 2.11 #1, #2: a custom role with the view right only reads and cannot enter or approve; a
     * role with the approve right alone cannot read; a member, the operator and a caller without a token get 403 and
     * 401 on every family (accounts, expenses, handovers, other income, counts, reconciliations, the cash book report).
     */
    @Test
    void everyEndpointFamilyHonoursTheThreeRightsForACustomRoleAMemberTheOperatorAndNoToken() throws Exception {
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "5000.00", opened).andExpect(status().isOk());
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        UUID expenseId = recordExpense(treasurer, expense("CASH", opened.plusDays(1), "10.00", materials));
        UUID countId = saveCount(treasurer, count(kyiv(opened.plusDays(2), 12), "990.00", "Різниця"));
        String viewOnly = customRole(tenantA, "CASHBOOK_VIEW");
        String approveOnly = customRole(tenantA, "CASHBOOK_APPROVE");
        String operator = operator();

        // the view-only role reads everything and enters or approves nothing
        accounts(viewOnly).andExpect(status().isOk());
        expenseCard(viewOnly, expenseId).andExpect(status().isOk());
        countCard(viewOnly, countId).andExpect(status().isOk());
        countAct(viewOnly, countId).andExpect(status().isOk());
        reconciliation(viewOnly, month).andExpect(status().isOk());
        report(viewOnly, "cash-book", "from", opened.toString(), "to", today().toString()).andExpect(status().isOk());
        createExpense(viewOnly, expense("CASH", today(), "1.00", materials)).andExpect(status().isForbidden());
        createHandover(viewOnly, handover(today(), "1.00", "КВ-1")).andExpect(status().isForbidden());
        createOtherIncome(viewOnly, otherIncome("BANK", today(), "1.00", category(tenantA, "INCOME", "BANK_INTEREST")))
                .andExpect(status().isForbidden());
        enterCount(viewOnly, count(kyiv(today(), 9), "1.00", "x")).andExpect(status().isForbidden());
        enterStatement(viewOnly, month, "1.00", "0.00", "0.00", "1.00").andExpect(status().isForbidden());
        bookDifference(viewOnly, countId, "r").andExpect(status().isForbidden());
        reverseExpense(viewOnly, expenseId, "r").andExpect(status().isForbidden());
        setOpening(viewOnly, cash, "1.00", opened).andExpect(status().isForbidden());

        // the treasurer enters but approves nothing
        reverseExpense(treasurer, expenseId, "r").andExpect(status().isForbidden());
        bookBankDifference(treasurer, month, "1.00", "r").andExpect(status().isForbidden());
        addCategory(treasurer, "EXPENSE", "Нова стаття").andExpect(status().isForbidden());

        // approve alone reads nothing; the chairman adds a category
        accounts(approveOnly).andExpect(status().isForbidden());
        expenseCard(approveOnly, expenseId).andExpect(status().isForbidden());
        addCategory(chairman, "EXPENSE", "Нова стаття").andExpect(status().isCreated());

        // a member and the operator are refused everywhere, no token is unauthorized
        for (String refused : new String[] {member, operator}) {
            accounts(refused).andExpect(status().isForbidden());
            expenseCard(refused, expenseId).andExpect(status().isForbidden());
            download(refused, expenseId, 1).andExpect(status().isForbidden());
            countCard(refused, countId).andExpect(status().isForbidden());
            countAct(refused, countId).andExpect(status().isForbidden());
            counts(refused).andExpect(status().isForbidden());
            reconciliation(refused, month).andExpect(status().isForbidden());
            send(refused, get(EXPENSES), null).andExpect(status().isForbidden());
            report(refused, "cash-book", "from", opened.toString(), "to", today().toString())
                    .andExpect(status().isForbidden());
            createExpense(refused, expense("CASH", today(), "1.00", materials)).andExpect(status().isForbidden());
        }
        accounts(null).andExpect(status().isUnauthorized());
        expenseCard(null, expenseId).andExpect(status().isUnauthorized());
        send(null, put(RECONCILIATIONS + "/" + month), Map.of("opening", "1.00", "incoming", "0.00", "outgoing",
                "0.00", "closing", "1.00")).andExpect(status().isUnauthorized());
    }

    // ---- FS 2.13 #4 and 2.3 #6, 2.6, 2.7, 2.10: a second cooperative with every permission

    /**
     * FS 2.13 #4: a chairman of another cooperative finds the expense, its attachment, the count and its act, the
     * movements, the reconciliation month data and the reconciliation act of this cooperative «not found», and cannot
     * reverse or book anything of it.
     */
    @Test
    void aChairmanOfAnotherCooperativeFindsNothingOfThisOneAndChangesNothing() throws Exception {
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
        setOpening(chairman, bank, "5000.00", opened).andExpect(status().isOk());
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        UUID expenseId = recordExpense(treasurer, expense("CASH", opened.plusDays(1), "10.00", materials),
                file("receipt.png", "image/png", TestImages.png()));
        UUID countId = saveCount(treasurer, count(kyiv(opened.plusDays(2), 12), "980.00", "Різниця"));
        UUID movementId = UUID.fromString(journal(treasurer, cash, opened).get(0).get("id").asString());
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, "
                + "iban) values (?, 'Інший кооператив', 'с. Інше', 'Банк', 'UA850000000000000000000123457')", tenantB);
        String foreign = person(tenantB, "CHAIRMAN");
        UUID foreignCash = account(tenantB, "CASH");
        setOpening(foreign, foreignCash, "50.00", opened).andExpect(status().isOk());

        expenseCard(foreign, expenseId).andExpect(status().isNotFound());
        download(foreign, expenseId, 1).andExpect(status().isNotFound());
        reverseExpense(foreign, expenseId, "Чужа").andExpect(status().isNotFound());
        countCard(foreign, countId).andExpect(status().isNotFound());
        countAct(foreign, countId).andExpect(status().isNotFound());
        bookDifference(foreign, countId, "Чужа").andExpect(status().isNotFound());
        movements(foreign, cash, opened, today()).andExpect(status().isNotFound());
        reverseMovement(foreign, foreignCash, movementId, "Чужа").andExpect(status().isNotFound());
        act(foreign, ownPlot, opened.plusDays(1), today()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        // their own reconciliation has no bank opening balance: none of our figures leak into it
        reconciliation(foreign, month).andExpect(status().isConflict());
        // nothing of ours changed
        assertThat(body(expenseCard(chairman, expenseId).andExpect(status().isOk())).get("reversed").asBoolean())
                .isFalse();
        assertThat(balance("CASH")).isEqualTo("990.00");
    }

    // ---- helpers

    private String balance(String kind) throws Exception {
        return accountOf(body(accounts(treasurer).andExpect(status().isOk())), kind).get("balance").asString();
    }

    private String monthStatus() throws Exception {
        for (JsonNode m : accountOf(body(accounts(treasurer).andExpect(status().isOk())), "BANK").get("months")) {
            if (m.get("month").asString().equals(month.toString())) {
                return m.get("status").asString();
            }
        }
        throw new AssertionError("No month " + month);
    }

    private ResultActions act(String bearer, UUID plot, LocalDate from, LocalDate to) throws Exception {
        return send(bearer, get("/api/v1/plots/" + plot + "/reconciliation-act.pdf").param("from", from.toString())
                .param("to", to.toString()), null);
    }

    private String plotNumber(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    private static List<String> permissionsOf(JsonNode roles, String baseCode) {
        for (JsonNode role : roles) {
            if (role.has("baseCode") && role.get("baseCode").asString().equals(baseCode)) {
                List<String> permissions = new ArrayList<>();
                role.get("permissions").forEach(p -> permissions.add(p.asString()));
                return permissions;
            }
        }
        throw new AssertionError("No role " + baseCode);
    }

    private static String pdfText(ResultActions call) throws IOException {
        byte[] pdf = call.andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
