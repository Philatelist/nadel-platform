package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Payments, cash desk and allocation" (functional-spec.md of 015) through the
 * public HTTP API, one test per criterion group of the spec, named after its section ({@code fs2_1 ...}). The users are
 * the demo ones: the chairman, the treasurer and the member of a cooperative of the test's own (so the totals are exact
 * and the append-only ledger leaves the demo cooperative alone); the electrician and the "other cooperative" are the
 * demo users, whose cooperative is a different one from the test's. The per-case rules (the planner, the split rounding,
 * the concurrency of receipt numbers, the take-back paths, the permission matrix, the audit and encryption details, the
 * reports) stay in the slice tests, which {@code context/qa/list-of-tests.md} lists next to this class; the criteria that
 * only a browser can check are in {@code web/e2e/payments.spec.ts}.
 * <pre>
 * @layer: integration
 * @spec: 015-payments-and-allocation
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(180)
class PaymentsAcceptanceTests extends FreshPaymentSupport {

    private static final String SPLIT_PAYER_NOTE = "Онука Оксана Петренко, +380671112233";

    @AfterEach
    void unlinkDemoUsers() {
        // a first sign-in links a demo member to its subject; other tests expect them unlinked
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "electrician@demo.local");
    }

    // ---- helpers

    private static String demoChairman() {
        return TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
    }

    private static String demoElectrician() {
        return TestTokens.bearer(DEMO_TENANT, "sub-electrician@demo.local", "electrician@demo.local");
    }

    private Api api() {
        return new Api();
    }

    /** Short calls of the payments API as one user. */
    private final class Api {
        JsonNode previewPart(String bearer, Map<String, Object> request) throws Exception {
            return body(send(bearer, post(PAYMENTS + "?preview=true"), request).andExpect(status().isOk()))
                    .get("parts").get(0);
        }

        JsonNode save(String bearer, Map<String, Object> request) throws Exception {
            return body(send(bearer, post(PAYMENTS + "?preview=false"), request).andExpect(status().isCreated()));
        }

        UUID saveOne(String bearer, Map<String, Object> request) throws Exception {
            return UUID.fromString(save(bearer, request).get("payments").get(0).get("id").asString());
        }

        JsonNode card(String bearer, UUID payment) throws Exception {
            return body(send(bearer, get(PAYMENTS + "/" + payment), null).andExpect(status().isOk()));
        }

        JsonNode account(String bearer, UUID plot) throws Exception {
            return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
        }
    }

    private static Map<String, Object> request(UUID plot, String source, String amount, String receivedOn,
            List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", receivedOn);
        if (lines != null) {
            body.put("lines", lines);
        }
        return body;
    }

    private static Map<String, Object> cash(UUID plot, String amount) {
        return request(plot, "CASH", amount, today().toString(), null);
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    private static JsonNode lineOf(JsonNode part, UUID charge) {
        for (JsonNode line : part.get("lines")) {
            if (line.get("chargeId").asString().equals(charge.toString())) {
                return line;
            }
        }
        throw new AssertionError("No proposal line for the charge " + charge);
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.get(field).asString()));
        return values;
    }

    private void owns(UUID plot, UUID owner) {
        owner().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", tenant, plot, owner);
    }

    private int paymentCount(UUID... plots) {
        int count = 0;
        for (UUID plot : plots) {
            count += owner().queryForObject("select count(*) from payment where plot_id = ?", Integer.class, plot);
        }
        return count;
    }

    private String statusOf(UUID plot) throws Exception {
        return body(send(chairman, get("/api/v1/plots/" + plot), null).andExpect(status().isOk())).get("status")
                .asString();
    }

    /** Example 1: membership 600.00 (January) and electricity 250.00 (February) of plot 9. */
    private record Debts(UUID plot, UUID membership, UUID electricity) {
    }

    private Debts example1() {
        UUID plot = plot("9", "600");
        return new Debts(plot, charge(plot, "MEMBERSHIP", "600.00", "2026-01"),
                charge(plot, "ELECTRICITY", "250.00", "2026-02"));
    }

    private void setting(boolean autoAllocate) throws Exception {
        send(chairman, put("/api/v1/settings"), Map.of("autoAllocatePrepayment", autoAllocate))
                .andExpect(status().isOk());
    }

    // ---- 2.1 Entering a payment for one plot

    @Test
    void fs2_1_700OnTheTwoDebtsPaysMembershipInFullAndElectricity100AndSaysTheReceipt() throws Exception {
        Debts debts = example1();

        JsonNode part = api().previewPart(treasurer, cash(debts.plot(), "700.00"));
        assertThat(values(part.get("lines"), "chargeId"))
                .containsExactly(debts.membership().toString(), debts.electricity().toString());
        assertThat(lineOf(part, debts.membership()).get("proposed").asString()).isEqualTo("600.00");
        assertThat(lineOf(part, debts.electricity()).get("proposed").asString()).isEqualTo("100.00");
        assertThat(part.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(paymentCount(debts.plot())).as("a preview writes nothing").isZero();

        // saving a cash payment: the cooperative's next receipt number (the first of a new cooperative)
        JsonNode saved = api().save(treasurer, cash(debts.plot(), "700.00")).get("payments").get(0);
        assertThat(saved.get("receiptNumber").asLong()).isEqualTo(1L);
        // 150.00 of the electricity stays open
        JsonNode next = api().previewPart(treasurer, cash(debts.plot(), "1.00"));
        assertThat(lineOf(next, debts.electricity()).get("open").asString()).isEqualTo("150.00");
        assertThat(next.get("lines")).hasSize(1);
    }

    @Test
    void fs2_1_1000CoversBothInFullAndLeavesAPrepaymentOf150() throws Exception {
        Debts debts = example1();

        JsonNode part = api().previewPart(treasurer, cash(debts.plot(), "1000.00"));

        assertThat(lineOf(part, debts.membership()).get("proposed").asString()).isEqualTo("600.00");
        assertThat(lineOf(part, debts.electricity()).get("proposed").asString()).isEqualTo("250.00");
        assertThat(part.get("prepayment").asString()).isEqualTo("150.00");
    }

    @Test
    void fs2_1_sameDebtDateGoesByTheOrderOfChargeTypes() throws Exception {
        UUID plot = plot("9", "600");
        // booked in the opposite order: the order of charge types (membership first) decides
        UUID electricity = charge(plot, "ELECTRICITY", "200.00", "2026-03");
        UUID membership = charge(plot, "MEMBERSHIP", "300.00", "2026-03");

        JsonNode part = api().previewPart(treasurer, cash(plot, "400.00"));

        assertThat(lineOf(part, membership).get("proposed").asString()).isEqualTo("300.00");
        assertThat(lineOf(part, electricity).get("proposed").asString()).isEqualTo("100.00");
    }

    @Test
    void fs2_1_anOpeningBalanceReceivesThePaymentAndKeepsWhatIsOpen() throws Exception {
        UUID plot = plot("9", "600");
        Map<String, Object> opening = new LinkedHashMap<>();
        opening.put("chargeTypeId", typeId("MEMBERSHIP").toString());
        opening.put("amount", "1200.00");
        opening.put("date", "2026-01-01");
        UUID balance = UUID.fromString(body(send(chairman, post(OPENING.formatted(plot)), opening)
                .andExpect(status().isCreated())).get("id").asString());
        awaitListeners();

        JsonNode part = api().save(treasurer, cash(plot, "500.00")).get("parts").get(0);

        assertThat(lineOf(part, balance).get("kind").asString()).isEqualTo("OPENING");
        assertThat(lineOf(part, balance).get("proposed").asString()).isEqualTo("500.00");
        assertThat(lineOf(api().previewPart(treasurer, cash(plot, "1.00")), balance).get("open").asString())
                .isEqualTo("700.00");
    }

    @Test
    void fs2_1_theTreasurersOwnLinesAreSavedAsChosen() throws Exception {
        Debts debts = example1();

        api().save(treasurer, request(debts.plot(), "CASH", "700.00", today().toString(),
                List.of(line(debts.electricity(), "250.00"), line(debts.membership(), "400.00"))));

        assertThat(netAllocated(debts.electricity())).isEqualByComparingTo("250.00");
        assertThat(netAllocated(debts.membership())).isEqualByComparingTo("400.00");
        assertThat(lineOf(api().previewPart(treasurer, cash(debts.plot(), "1.00")), debts.membership())
                .get("open").asString()).isEqualTo("200.00");
    }

    @Test
    void fs2_1_everyRefusalNamesItsCodeAndSavesNothingNorUsesUpAReceiptNumber() throws Exception {
        Debts debts = example1();
        UUID common = plot("C", "0", "COMMON", false);
        UUID archived = plot("A", "600", "PLOT", true);
        String today = today().toString();

        // more allocated than the payment
        send(treasurer, post(PAYMENTS + "?preview=false"), request(debts.plot(), "CASH", "700.00", today,
                List.of(line(debts.membership(), "600.00"), line(debts.electricity(), "300.00"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"));
        // more on a line than is open on its charge
        send(treasurer, post(PAYMENTS + "?preview=false"), request(debts.plot(), "CASH", "700.00", today,
                List.of(line(debts.electricity(), "250.01"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_CHARGE"));
        // an amount of zero, a date in the future
        send(treasurer, post(PAYMENTS + "?preview=false"), cash(debts.plot(), "0.00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_NOT_POSITIVE"));
        send(treasurer, post(PAYMENTS + "?preview=false"), request(debts.plot(), "CASH", "10.00",
                today().plusDays(1).toString(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("receivedOn"))
                .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        // common land and an archived plot
        send(treasurer, post(PAYMENTS + "?preview=false"), cash(common, "10.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        send(treasurer, post(PAYMENTS + "?preview=false"), cash(archived, "10.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        // another user booked a charge after the proposal was shown
        String digest = body(send(treasurer, post(PAYMENTS + "?preview=true"), cash(debts.plot(), "700.00"))
                .andExpect(status().isOk())).get("digest").asString();
        charge(debts.plot(), "LAND_TAX", "120.00", "2026-03");
        send(treasurer, post(PAYMENTS + "?preview=false"), with(cash(debts.plot(), "700.00"), "digest", digest))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(paymentCount(debts.plot(), common, archived)).isZero();
        assertThat(owner().queryForObject("select count(*) from allocation where tenant_id = ?", Integer.class,
                tenant)).isZero();
        assertThat(owner().queryForList("select last_number from receipt_counter where tenant_id = ?", Long.class,
                tenant)).as("no receipt number used up").isEmpty();
        // the next good payment is receipt number 1
        assertThat(api().save(treasurer, cash(debts.plot(), "10.00")).get("payments").get(0).get("receiptNumber")
                .asLong()).isEqualTo(1L);
    }

    @Test
    void fs2_1_aPaymentOfAClosedMonthCountsInTheEarliestOpenMonthAndKeepsItsDate() throws Exception {
        UUID plot = plot("9", "600");
        String closed = ym(1);
        closeMonth(closed);
        String lastDay = current().minusMonths(1).atEndOfMonth().toString();

        JsonNode saved = api().save(treasurer, request(plot, "CASH", "100.00", lastDay, null)).get("payments").get(0);

        assertThat(saved.get("billingMonth").asString()).isEqualTo(ym(0));
        assertThat(saved.get("receivedOn").asString()).isEqualTo(lastDay);
        JsonNode months = body(send(treasurer, get("/api/v1/billing-months"), null).andExpect(status().isOk()));
        for (JsonNode month : months) {
            if (month.get("month").asString().equals(closed)) {
                assertThat(month.get("received").asString()).as("the closed month's totals").isEqualTo("0.00");
            }
        }
    }

    // ---- 2.2 One payment for several plots of one payer

    private record Plots(UUID seven, UUID fifteen, UUID sevenCharge, UUID fifteenCharge) {
    }

    /** The member owns plot 7 (debt 300.00) and plot 15 (debt 100.00). */
    private Plots sevenAndFifteen() {
        UUID seven = plot("7", "600");
        UUID fifteen = plot("15", "600");
        owns(seven, memberId);
        owns(fifteen, memberId);
        return new Plots(seven, fifteen, charge(seven, "MEMBERSHIP", "300.00", "2026-01"),
                charge(fifteen, "MEMBERSHIP", "100.00", "2026-01"));
    }

    private Map<String, Object> split(String source, String amount, List<Map<String, Object>> parts) {
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("payerMemberId", memberId.toString());
        if (parts != null) {
            split.put("parts", parts);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        body.put("split", split);
        return body;
    }

    private Map<String, Object> part(UUID plot, String amount) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("plotId", plot.toString());
        part.put("amount", amount);
        return part;
    }

    @Test
    void fs2_2_theSplitIsProposedInProportionToTheDebtsWithKopecksAndExcessRules() throws Exception {
        Plots plots = sevenAndFifteen();

        JsonNode proportional = body(send(treasurer, post(PAYMENTS + "?preview=true"), split("CASH", "200.00", null))
                .andExpect(status().isOk()));
        assertThat(values(proportional.get("parts"), "plotId"))
                .containsExactly(plots.seven().toString(), plots.fifteen().toString());
        assertThat(values(proportional.get("parts"), "amount")).containsExactly("150.00", "50.00");

        JsonNode excess = body(send(treasurer, post(PAYMENTS + "?preview=true"), split("CASH", "500.00", null))
                .andExpect(status().isOk()));
        assertThat(values(excess.get("parts"), "amount")).containsExactly("400.00", "100.00");
        assertThat(values(excess.get("parts"), "prepayment")).containsExactly("100.00", "0.00");
    }

    @Test
    void fs2_2_threePlotsOf100GetTheLeftOverKopeckOnTheFirst() throws Exception {
        for (String number : List.of("7", "15", "16")) {
            UUID plot = plot(number, "600");
            owns(plot, memberId);
            charge(plot, "MEMBERSHIP", "100.00", "2026-01");
        }

        JsonNode thirds = body(send(treasurer, post(PAYMENTS + "?preview=true"), split("CASH", "100.00", null))
                .andExpect(status().isOk()));

        assertThat(values(thirds.get("parts"), "amount")).containsExactly("33.34", "33.33", "33.33");
    }

    @Test
    void fs2_2_partsThatDoNotAddUpAreRefusedAndASavedCashSplitSharesOneReceipt() throws Exception {
        Plots plots = sevenAndFifteen();

        send(treasurer, post(PAYMENTS + "?preview=false"), split("CASH", "200.00",
                List.of(part(plots.seven(), "150.00"), part(plots.fifteen(), "40.00"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPLIT_TOTAL_MISMATCH"));
        assertThat(paymentCount(plots.seven(), plots.fifteen())).isZero();

        JsonNode saved = api().save(treasurer, split("CASH", "200.00", null));

        assertThat(saved.get("payments")).hasSize(2);
        List<String> receipts = new ArrayList<>();
        saved.get("payments").forEach(p -> receipts.add(p.get("receiptNumber").asString()));
        assertThat(receipts).containsOnly("1");
        // each plot's account shows its own payment with the receipt number and a link to the other part
        UUID sevenPayment = paymentOf(plots.seven());
        UUID fifteenPayment = paymentOf(plots.fifteen());
        JsonNode sevenAccount = api().account(treasurer, plots.seven());
        assertThat(values(sevenAccount.get("payments"), "id")).containsExactly(sevenPayment.toString());
        assertThat(sevenAccount.get("payments").get(0).get("receiptNumber").asLong()).isEqualTo(1L);
        JsonNode card = api().card(treasurer, sevenPayment);
        assertThat(values(card.get("groupParts"), "id")).containsExactly(fifteenPayment.toString());
        assertThat(values(api().card(treasurer, fifteenPayment).get("groupParts"), "id"))
                .containsExactly(sevenPayment.toString());
        assertThat(values(card.get("allocations"), "amount")).containsExactly("150.00");
    }

    private UUID paymentOf(UUID plot) {
        return owner().queryForObject("select id from payment where plot_id = ? and kind = 'PAYMENT'", UUID.class,
                plot);
    }

    // ---- 2.3 Receipt numbers

    @Test
    void fs2_3_receiptsAreSequentialAndAReversedOneKeepsItsNumberAndIsListedReversed() throws Exception {
        UUID plot = plot("9", "600");
        UUID first = api().saveOne(treasurer, cash(plot, "10.00"));
        UUID second = api().saveOne(treasurer, cash(plot, "10.00"));
        UUID third = api().saveOne(treasurer, cash(plot, "10.00"));
        assertThat(List.of(first, second, third)).extracting(id -> api().card(treasurer, id)
                .get("receiptNumber").asLong()).containsExactly(1L, 2L, 3L);

        send(chairman, post(PAYMENTS + "/" + second + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isCreated());
        UUID fourth = api().saveOne(treasurer, cash(plot, "10.00"));

        assertThat(api().card(treasurer, fourth).get("receiptNumber").asLong()).isEqualTo(4L);
        JsonNode reversed = api().card(treasurer, second);
        assertThat(reversed.get("receiptNumber").asLong()).isEqualTo(2L);
        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        JsonNode list = body(send(treasurer, get(PAYMENTS + "?source=CASH&plotId=" + plot), null)
                .andExpect(status().isOk()));
        assertThat(list.get("items")).hasSize(4);
        for (JsonNode item : list.get("items")) {
            assertThat(item.get("reversed").asBoolean()).isEqualTo(item.get("id").asString().equals(second.toString()));
        }
        // two treasurers at the same moment: ReceiptNumberConcurrencyTests
    }

    // ---- 2.4 Payments list and payment card

    @Test
    void fs2_4_theSourceFilterListsOnlyCashWithReceiptsNewestFirstAndBankHasNone() throws Exception {
        UUID plot = plot("9", "600");
        UUID cashOne = api().saveOne(treasurer, cash(plot, "10.00"));
        UUID bank = api().saveOne(treasurer, with(request(plot, "BANK", "20.00", today().toString(), null),
                "bankReference", "ПД-17"));
        UUID cashTwo = api().saveOne(treasurer, cash(plot, "30.00"));

        JsonNode cashList = body(send(treasurer, get(PAYMENTS + "?source=CASH"), null).andExpect(status().isOk()));
        assertThat(values(cashList.get("items"), "id")).containsExactlyInAnyOrder(cashOne.toString(),
                cashTwo.toString());
        cashList.get("items").forEach(item -> assertThat(item.has("receiptNumber")).isTrue());
        JsonNode bankCard = api().card(treasurer, bank);
        assertThat(bankCard.has("receiptNumber")).isFalse();
        assertThat(bankCard.get("bankReference").asString()).isEqualTo("ПД-17");
    }

    // ---- 2.5 Reversing a payment

    @Test
    void fs2_5_theChairmanReversesOnceWithAReasonAndTheDebtsAreBack() throws Exception {
        Debts debts = example1();
        UUID payment = api().saveOne(treasurer, cash(debts.plot(), "700.00"));

        // not offered to the treasurer, refused by the API
        assertThat(api().card(treasurer, payment).get("reversible").asBoolean()).isFalse();
        assertThat(api().card(chairman, payment).get("reversible").asBoolean()).isTrue();
        send(treasurer, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", "Помилкова ділянка"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        // a reason is required
        send(chairman, post(PAYMENTS + "/" + payment + "/reverse"), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        JsonNode reversed = body(send(chairman, post(PAYMENTS + "/" + payment + "/reverse"),
                Map.of("reason", "Помилкова ділянка")).andExpect(status().isCreated()));
        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        assertThat(reversed.get("reversedBy").get("reason").asString()).isEqualTo("Помилкова ділянка");
        JsonNode part = api().previewPart(treasurer, cash(debts.plot(), "1.00"));
        assertThat(lineOf(part, debts.membership()).get("open").asString()).isEqualTo("600.00");
        assertThat(lineOf(part, debts.electricity()).get("open").asString()).isEqualTo("250.00");

        send(chairman, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", "Ще раз"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));
    }

    // ---- 2.6 Reallocating a payment and allocating a prepayment

    @Test
    void fs2_6_reallocatingMovesTheMoneyAndTheCardListsTheReleasedAndTheNewAllocations() throws Exception {
        Debts debts = example1();
        UUID payment = api().saveOne(treasurer, cash(debts.plot(), "700.00"));

        send(treasurer, post(PAYMENTS + "/" + payment + "/reallocate?preview=false"), Map.of("lines",
                List.of(line(debts.electricity(), "250.00"), line(debts.membership(), "450.00"))))
                .andExpect(status().isCreated());

        JsonNode part = api().previewPart(treasurer, cash(debts.plot(), "1.00"));
        assertThat(part.get("lines")).hasSize(1);
        assertThat(lineOf(part, debts.membership()).get("open").asString()).isEqualTo("150.00");
        JsonNode card = api().card(treasurer, payment);
        assertThat(card.get("allocations")).hasSize(6);
        assertThat(card.get("allocations").findValuesAsString("releaseCause")).containsOnly("REALLOCATED");
        assertThat(card.get("allocations").findValuesAsString("origin"))
                .containsExactlyInAnyOrder("ENTRY", "ENTRY", "REALLOCATION", "REALLOCATION");
    }

    @Test
    void fs2_6_withAutomaticAllocationOffThePrepaymentIsAllocatedByHandAndTheRestStays() throws Exception {
        setting(false);
        Debts debts = example1();
        api().save(treasurer, cash(debts.plot(), "1000.00"));
        UUID landTax = charge(debts.plot(), "LAND_TAX", "120.00", "2026-03");
        assertThat(prepayment(debts.plot())).isEqualByComparingTo("150.00");

        JsonNode proposal = body(send(treasurer, post("/api/v1/plots/" + debts.plot()
                + "/prepayment/allocate?preview=true"), Map.of()).andExpect(status().isOk()));
        assertThat(proposal.toString()).contains(landTax.toString());
        send(treasurer, post("/api/v1/plots/" + debts.plot() + "/prepayment/allocate?preview=false"),
                Map.of("lines", List.of(line(landTax, "120.00")))).andExpect(status().isCreated());

        assertThat(open(landTax)).isEqualByComparingTo("0.00");
        assertThat(prepayment(debts.plot())).isEqualByComparingTo("30.00");
    }

    // ---- 2.7 Prepayment and automatic allocation

    private void landTaxBookedByTheMonthRun() throws Exception {
        tariff("LAND_TAX", "Земельний податок", "PER_PLOT", "MONTHLY", "120.0000", "2020-01-01");
        send(chairman, post(RUN.formatted(ym(0), false)), null).andExpect(status().isOk());
        awaitListeners();
    }

    @Test
    void fs2_7_aNewChargeIsPaidFromThePrepaymentAutomaticallyAndTheAccountSaysSo() throws Exception {
        Debts debts = example1();
        api().save(treasurer, cash(debts.plot(), "1000.00"));
        awaitListeners();
        assertThat(prepayment(debts.plot())).isEqualByComparingTo("150.00");

        landTaxBookedByTheMonthRun();

        UUID landTax = bookedCharge(debts.plot(), "LAND_TAX", ym(0));
        assertThat(open(landTax)).isEqualByComparingTo("0.00");
        assertThat(allocations(debts.plot(), "PREPAYMENT_AUTO")).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("120.00");
            assertThat(row.get("created_by")).isNull();
        });
        JsonNode account = api().account(treasurer, debts.plot());
        assertThat(account.get("prepayment").asString()).isEqualTo("30.00");
        assertThat(account.get("prepaymentAllocations")).hasSize(1);
        assertThat(account.get("prepaymentAllocations").get(0).get("origin").asString()).isEqualTo("PREPAYMENT_AUTO");
    }

    @Test
    void fs2_7_withTheSettingOffTheNewChargeStaysOpenAndThePrepaymentStays() throws Exception {
        setting(false);
        Debts debts = example1();
        api().save(treasurer, cash(debts.plot(), "1000.00"));
        awaitListeners();

        landTaxBookedByTheMonthRun();

        assertThat(open(bookedCharge(debts.plot(), "LAND_TAX", ym(0)))).isEqualByComparingTo("120.00");
        assertThat(prepayment(debts.plot())).isEqualByComparingTo("150.00");
        assertThat(allocations(debts.plot(), "PREPAYMENT_AUTO")).isEmpty();
    }

    @Test
    void fs2_7_theChairmanSwitchesTheSettingOffItStaysOffAndATreasurerCannotChangeIt() throws Exception {
        assertThat(body(send(treasurer, get("/api/v1/settings"), null).andExpect(status().isOk()))
                .get("autoAllocatePrepayment").asBoolean()).isTrue();

        setting(false);

        assertThat(body(send(chairman, get("/api/v1/settings"), null).andExpect(status().isOk()))
                .get("autoAllocatePrepayment").asBoolean()).isFalse();
        send(treasurer, put("/api/v1/settings"), Map.of("autoAllocatePrepayment", true))
                .andExpect(status().isForbidden());
        assertThat(body(send(treasurer, get("/api/v1/settings"), null).andExpect(status().isOk()))
                .get("autoAllocatePrepayment").asBoolean()).isFalse();
    }

    // ---- 2.8 When paid money is taken back automatically

    @Test
    void fs2_8_reversingAPaidChargeTakesTheMoneyBackAndTheOpenElectricityGets150() throws Exception {
        Debts debts = example1();
        api().save(treasurer, cash(debts.plot(), "700.00"));

        reverseCharge(debts.membership());
        awaitListeners();

        assertThat(releases(debts.plot())).singleElement()
                .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-600.00"));
        assertThat(open(debts.electricity())).isEqualByComparingTo("0.00");
        assertThat(prepayment(debts.plot())).isEqualByComparingTo("450.00");
        // the lowering and raising of a paid charge by a recalculation: AutoReleaseTests (examples 7 and 8)
    }

    // ---- 2.9 Debt, map colour and the plot account

    @Test
    void fs2_9_aPaymentLowersTheDebtAndTheMapColourFollowsWithinSeconds() throws Exception {
        UUID plot = plot("9", "600");
        owns(plot, memberId);
        charge(plot, "MEMBERSHIP", "1300.00", ym(0));
        api().save(treasurer, cash(plot, "100.00"));
        awaitListeners();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(plot)).isEqualTo("RED"));

        api().save(treasurer, cash(plot, "900.00"));

        awaitListeners();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(plot)).isEqualTo("YELLOW"));
        JsonNode account = api().account(treasurer, plot);
        assertThat(account.get("total").asString()).isEqualTo("300.00");
        assertThat(account.get("payments")).hasSize(2);
    }

    @Test
    void fs2_9_aFullyPaidPlotWithALeftOverIsGreenWithAPrepaymentAndPaymentsOnlyKeepsTheDebts() throws Exception {
        UUID plot = plot("9", "600");
        owns(plot, memberId);
        charge(plot, "MEMBERSHIP", "100.00", ym(0));
        UUID payment = api().saveOne(treasurer, cash(plot, "150.00"));
        awaitListeners();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(plot)).isEqualTo("GREEN"));

        JsonNode account = api().account(chairman, plot);
        assertThat(account.get("prepayment").asString()).isEqualTo("50.00");
        assertThat(account.get("total").asString()).isEqualTo("0.00");
        send(chairman, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isCreated());

        JsonNode only = body(send(treasurer, get(ACCOUNT.formatted(plot)).param("kind", "PAYMENTS"), null)
                .andExpect(status().isOk()));
        assertThat(only.get("charges")).isEmpty();
        assertThat(only.get("payments").findValuesAsString("kind")).containsExactlyInAnyOrder("PAYMENT",
                "PAYMENT_REVERSAL");
        assertThat(only.get("debts")).isEqualTo(api().account(treasurer, plot).get("debts"));
    }

    // ---- 2.10 What a member sees

    @Test
    void fs2_10_aMemberSeesTheirPaymentWithTheReceiptAndNoNoteOrAuthorAndNotFoundForAnotherPlot() throws Exception {
        UUID own = plot("15", "600");
        owns(own, memberId);
        UUID foreign = plot("16", "600");
        charge(own, "MEMBERSHIP", "300.00", "2026-01");
        charge(foreign, "MEMBERSHIP", "300.00", "2026-01");
        api().save(treasurer, with(cash(own, "100.00"), "payerNote", SPLIT_PAYER_NOTE));
        api().save(treasurer, cash(foreign, "50.00"));

        JsonNode account = api().account(member, own);

        assertThat(account.get("total").asString()).isEqualTo("200.00");
        assertThat(account.get("payments")).hasSize(1);
        JsonNode payment = account.get("payments").get(0);
        assertThat(payment.get("receiptNumber").asLong()).isEqualTo(1L);
        assertThat(payment.get("amount").asString()).isEqualTo("100.00");
        assertThat(payment.has("payerNote")).isFalse();
        assertThat(payment.has("createdByName")).isFalse();
        assertThat(account.toString()).doesNotContain("Оксана");

        send(member, get(ACCOUNT.formatted(foreign)), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.payments").doesNotExist()).andExpect(jsonPath("$.total").doesNotExist());
    }

    // ---- 2.11 Reports and the billing months screen

    /** The cells of the row whose first cell is the plot number, by column key. */
    private static Map<String, String> row(JsonNode sheet, String number) {
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        for (JsonNode row : sheet.get("rows")) {
            JsonNode cells = row.get("cells");
            if (cells.get(0).get("value").asString().equals(number)) {
                Map<String, String> byKey = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    byKey.put(keys.get(i), cells.get(i).has("value") ? cells.get(i).get("value").asString() : "-");
                }
                return byKey;
            }
        }
        return null;
    }

    @Test
    void fs2_11_plotBalancesAndDebtorsReflectThePaymentsAndAPaidPlotIsNotADebtor() throws Exception {
        UUID nine = plot("9", "600");
        UUID ten = plot("10", "600");
        charge(nine, "MEMBERSHIP", "600.00", "2026-01");
        charge(nine, "ELECTRICITY", "250.00", "2026-02");
        charge(ten, "MEMBERSHIP", "100.00", "2026-01");
        api().save(treasurer, cash(nine, "700.00"));
        api().save(treasurer, cash(ten, "100.00"));
        awaitListeners();

        JsonNode balances = body(send(treasurer, get("/api/v1/reports/plot-balances"), null)
                .andExpect(status().isOk())).get("sheets").get(0);
        Map<String, String> nineRow = row(balances, "9");
        assertThat(nineRow).containsEntry("charged", "850.00").containsEntry("paid", "700.00")
                .containsEntry("total", "150.00").containsEntry("prepayment", "0.00");

        JsonNode debtors = body(send(treasurer, get("/api/v1/reports/debtors"), null).andExpect(status().isOk()))
                .get("sheets").get(0);
        assertThat(row(debtors, "9")).isNotNull();
        assertThat(row(debtors, "10")).as("a fully paid plot is not listed").isNull();
    }

    @Test
    void fs2_11_thePaymentsReportListsEachCashPaymentOfTheMonthWithATotalAndBillingMonthsShowReceived()
            throws Exception {
        UUID plot = plot("9", "600");
        api().saveOne(treasurer, cash(plot, "40.00"));
        api().saveOne(treasurer, cash(plot, "60.00"));
        api().saveOne(treasurer, request(plot, "BANK", "25.00", today().toString(), null));

        JsonNode report = body(send(treasurer, get("/api/v1/reports/payments").param("fromMonth", ym(0))
                .param("toMonth", ym(0)).param("source", "CASH"), null).andExpect(status().isOk()));
        JsonNode sheet = report.get("sheets").get(0);
        assertThat(sheet.get("rows")).hasSize(2);
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        int amount = keys.indexOf("amount");
        JsonNode cashTotal = null;
        for (JsonNode total : sheet.get("totals")) {
            if (total.get("key").asString().equals("CASH")) {
                cashTotal = total;
            }
        }
        assertThat(cashTotal).isNotNull();
        assertThat(cashTotal.get("cells").get(amount).get("value").asString()).isEqualTo("100.00");

        JsonNode months = body(send(treasurer, get("/api/v1/billing-months"), null).andExpect(status().isOk()));
        JsonNode current = null;
        for (JsonNode month : months) {
            if (month.get("month").asString().equals(ym(0))) {
                current = month;
            }
        }
        assertThat(current).isNotNull();
        assertThat(current.get("received").asString()).isEqualTo("125.00");
        // the "paid" column of charges by month, the payments template fields: ReportsAcceptanceTests, report tests
    }

    // ---- 2.12 Permissions

    @Test
    void fs2_12_theElectricianAndAnotherCooperativeAreRefusedAndAnUnrelatedMemberFindsNothing() throws Exception {
        UUID plot = plot("9", "600");
        UUID payment = api().saveOne(treasurer, cash(plot, "10.00"));

        send(demoElectrician(), get(PAYMENTS), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(demoElectrician(), get(PAYMENTS + "/" + payment), null).andExpect(status().isForbidden());
        // the chairman of another cooperative has every permission, and finds nothing
        send(demoChairman(), get(PAYMENTS + "/" + payment), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(demoChairman(), post(PAYMENTS + "?preview=false"), cash(plot, "10.00")).andExpect(status().isNotFound());
        assertThat(body(send(demoChairman(), get(PAYMENTS + "?plotId=" + plot), null).andExpect(status().isOk()))
                .get("totalItems").asLong()).isZero();
        send(member, get(PAYMENTS), null).andExpect(status().isForbidden());
        assertThat(paymentCount(plot)).isOne();
        // the full matrix of every endpoint and role: PaymentsPermissionTests
    }

    @Test
    void fs2_12_enteredReallocatedAndReversedAreThreeAuditEntriesAndTheNoteIsNotReadableAnywhere() throws Exception {
        Debts debts = example1();
        UUID payment = api().saveOne(treasurer, with(cash(debts.plot(), "700.00"), "payerNote", SPLIT_PAYER_NOTE));
        send(treasurer, post(PAYMENTS + "/" + payment + "/reallocate?preview=false"), Map.of("lines",
                List.of(line(debts.electricity(), "250.00"), line(debts.membership(), "450.00"))))
                .andExpect(status().isCreated());
        send(chairman, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", "Помилкова ділянка"))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode page = body(send(chairman, get("/api/v1/audit").param("entityType", "PAYMENT")
                    .param("entityId", debts.plot().toString()), null).andExpect(status().isOk()));
            assertThat(values(page.get("items"), "action")).containsExactlyInAnyOrder("PAYMENT_RECORDED",
                    "PAYMENT_REALLOCATED", "PAYMENT_REVERSED");
        });

        // the stored data: sealed in the payment and in the audit entry
        byte[] stored = owner().queryForObject("select payer_note_enc from payment where id = ?", byte[].class,
                payment);
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("Оксана");
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? and "
                + "cast(changes as text) like ?", Integer.class, tenant, "%Оксана%")).isZero();
        assertThat(owner().queryForObject("select count(*) from event_publication where serialized_event like ?",
                Integer.class, "%Оксана%")).isZero();
        // the treasurer (who may view members) reads it on the card; the member never does
        assertThat(api().card(treasurer, payment).get("payerNote").asString()).isEqualTo(SPLIT_PAYER_NOTE);
    }

    // ---- 2.13 Demo data, docs and guides (the demo cooperative as the app ships it)

    // the demo payments (cash, bank, reversed, split over plots 7 and 15, all colours): DemoLedgerDataTests

    @Test
    void fs2_13_bothBoardGuidesHaveTheChapterAndPartTwoExplainsTheAllocationOrder() throws IOException {
        // the PDFs are built from these pages (make guide); the examples are the ones of 2.1, 2.2, 2.7 and 2.8
        String uk = Files.readString(Path.of("..", "docs", "guide", "uk", "board-guide.html"));
        String en = Files.readString(Path.of("..", "docs", "guide", "en", "board-guide.html"));
        assertThat(uk).contains("Платежі та каса");
        assertThat(en).contains("Payments and cash desk");
        assertThat(uk).contains("700,00").contains("150,00").contains("200 × 300 / 400 = 150,00");
        assertThat(en).contains("700.00").contains("150.00").contains("200 × 300 / 400 = 150.00");
    }
}
