package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * One payment split over several plots of one payer end to end ({@code POST /payments?preview=} with {@code split},
 * feature 015 §2.2 and §2.6, worked examples 12, 13, 14 and 15): the payer's current plots in natural order with their
 * debts, the proposed split, the caller's parts and their lines, the total that must match, plots that are not the
 * payer's, the remainder of each part placed at once on its plot while automatic allocation is on, the problems of
 * the parts answered by a preview, the whole amount to the first plot when nothing is owed, one group and one receipt
 * number for a cash split,
 * no receipt for a bank split, the card and the list showing the group, and a stale digest.
 */
class PaymentSplitTests extends PaymentApiSupport {

    /** The plot numbers of a test start here, so the natural order of its plots is the order of their suffixes. */
    private long base;

    @BeforeEach
    void chooseNumbers() {
        base = ThreadLocalRandom.current().nextLong(10_000, 90_000) * 100;
    }

    /** A member of the demo cooperative who owns no plot yet; removed after the test. */
    private UUID payer() {
        return newMember(DEMO_TENANT, "payer-" + UUID.randomUUID() + "@split.test");
    }

    /** A plot numbered {@code base + suffix}, owned by the payer. */
    private UUID plotOf(UUID payer, int suffix) {
        UUID plot = newPlot();
        ownerJdbc().update("update plot set number = ? where id = ?", String.valueOf(base + suffix), plot);
        own(payer, plot);
        return plot;
    }

    /** The two plots of examples 12, 14 and 15: plot 7 owes membership 300.00, plot 15 owes membership 100.00. */
    private record TwoPlots(UUID payer, UUID seven, UUID fifteen, UUID sevenCharge, UUID fifteenCharge) {
    }

    private TwoPlots twoPlots() {
        UUID payer = payer();
        UUID seven = plotOf(payer, 7);
        UUID fifteen = plotOf(payer, 15);
        return new TwoPlots(payer, seven, fifteen, tariffCharge(seven, "MEMBERSHIP", "300.00", "2026-01"),
                tariffCharge(fifteen, "MEMBERSHIP", "100.00", "2026-01"));
    }

    /** A split request received today; {@code parts} null uses the proposed split. */
    private static Map<String, Object> split(String source, String amount, UUID payer,
            List<Map<String, Object>> parts) {
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("payerMemberId", payer.toString());
        if (parts != null) {
            split.put("parts", parts);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", kyivToday().toString());
        body.put("split", split);
        return body;
    }

    /** A part of a split; {@code amount} null takes the proposed part, {@code lines} null the proposal. */
    private static Map<String, Object> part(UUID plot, String amount, List<Map<String, Object>> lines) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("plotId", plot.toString());
        if (amount != null) {
            part.put("amount", amount);
        }
        if (lines != null) {
            part.put("lines", lines);
        }
        return part;
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.get(field).asString()));
        return values;
    }

    private BigDecimal allocated(UUID charge) {
        return ownerJdbc().queryForObject("select coalesce(sum(amount), 0) from allocation where charge_id = ?",
                BigDecimal.class, charge);
    }

    private List<Map<String, Object>> paymentsOf(UUID... plots) {
        String ids = "{" + String.join(",", java.util.Arrays.stream(plots).map(UUID::toString).toList()) + "}";
        return ownerJdbc().queryForList("select id, plot_id, amount, group_id, receipt_number, payer_member_id "
                + "from payment where plot_id = any(?::uuid[]) order by plot_id", ids);
    }

    @Test
    void example12TheProposalIsInProportionToTheDebts() throws Exception {
        TwoPlots plots = twoPlots();

        JsonNode preview = body(preview(treasurer(), split("CASH", "200.00", plots.payer(), null))
                .andExpect(status().isOk()));

        JsonNode split = preview.get("split");
        assertThat(split.get("payerMemberId").asString()).isEqualTo(plots.payer().toString());
        assertThat(values(split.get("plots"), "plotId"))
                .containsExactly(plots.seven().toString(), plots.fifteen().toString());
        assertThat(values(split.get("plots"), "debt")).containsExactly("300.00", "100.00");
        assertThat(values(split.get("plots"), "proposedAmount")).containsExactly("150.00", "50.00");
        assertThat(values(split.get("plots"), "plotNumber"))
                .containsExactly(String.valueOf(base + 7), String.valueOf(base + 15));
        JsonNode parts = preview.get("parts");
        assertThat(values(parts, "plotId")).containsExactly(plots.seven().toString(), plots.fifteen().toString());
        assertThat(values(parts, "amount")).containsExactly("150.00", "50.00");
        assertThat(values(parts, "debt")).containsExactly("300.00", "100.00");
        assertThat(values(parts, "allocatedTotal")).containsExactly("150.00", "50.00");
        assertThat(values(parts, "prepayment")).containsExactly("0.00", "0.00");
        assertThat(parts.get(0).get("lines").get(0).get("chargeId").asString())
                .isEqualTo(plots.sevenCharge().toString());
        assertThat(parts.get(0).get("lines").get(0).get("proposed").asString()).isEqualTo("150.00");
        assertThat(preview.get("payments")).isEmpty();
        assertThat(paymentsOf(plots.seven(), plots.fifteen())).isEmpty();

        // parts named without amounts take the proposed parts, and save as previewed
        save(treasurer(), with(split("CASH", "200.00", plots.payer(),
                List.of(part(plots.fifteen(), null, null), part(plots.seven(), null, null))), "digest",
                preview.get("digest").asString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parts[0].plotId").value(plots.seven().toString()))
                .andExpect(jsonPath("$.payments.length()").value(2));
        assertThat(allocated(plots.sevenCharge())).isEqualByComparingTo("150.00");
        assertThat(allocated(plots.fifteenCharge())).isEqualByComparingTo("50.00");
    }

    @Test
    void example13TheFirstPlotGetsTheLeftoverKopeck() throws Exception {
        UUID payer = payer();
        List<UUID> plots = List.of(plotOf(payer, 1), plotOf(payer, 2), plotOf(payer, 10));
        List<UUID> charges = plots.stream().map(plot -> tariffCharge(plot, "MEMBERSHIP", "100.00", "2026-02"))
                .toList();

        JsonNode saved = body(save(treasurer(), split("BANK", "100.00", payer, null))
                .andExpect(status().isCreated()));

        assertThat(values(saved.get("parts"), "plotId"))
                .containsExactlyElementsOf(plots.stream().map(UUID::toString).toList());
        assertThat(values(saved.get("parts"), "amount")).containsExactly("33.34", "33.33", "33.33");
        assertThat(values(saved.get("split").get("plots"), "proposedAmount"))
                .containsExactly("33.34", "33.33", "33.33");
        assertThat(allocated(charges.get(0))).isEqualByComparingTo("33.34");
        assertThat(allocated(charges.get(1))).isEqualByComparingTo("33.33");
        assertThat(allocated(charges.get(2))).isEqualByComparingTo("33.33");
    }

    @Test
    void example14TheAmountAboveTheTotalDebtGoesToTheFirstPlotAsPrepayment() throws Exception {
        TwoPlots plots = twoPlots();

        JsonNode parts = body(save(treasurer(), split("CASH", "500.00", plots.payer(), null))
                .andExpect(status().isCreated())).get("parts");

        assertThat(values(parts, "amount")).containsExactly("400.00", "100.00");
        assertThat(values(parts, "allocatedTotal")).containsExactly("300.00", "100.00");
        assertThat(values(parts, "prepayment")).containsExactly("100.00", "0.00");
        assertThat(allocated(plots.sevenCharge())).isEqualByComparingTo("300.00");
        assertThat(allocated(plots.fifteenCharge())).isEqualByComparingTo("100.00");
        assertThat(ownerJdbc().queryForObject("select amount from payment where plot_id = ?", BigDecimal.class,
                plots.seven())).isEqualByComparingTo("400.00");
    }

    @Test
    void example15ACashSplitIsOneGroupWithOneReceiptNumber() throws Exception {
        TwoPlots plots = twoPlots();
        Long before = lastReceipt();
        long expected = before == null ? 1 : before + 1;

        JsonNode saved = body(save(treasurer(), with(split("CASH", "200.00", plots.payer(), null), "payerNote",
                "Сусід")).andExpect(status().isCreated())).get("payments");

        assertThat(saved).hasSize(2);
        assertThat(values(saved, "plotId")).containsExactly(plots.seven().toString(), plots.fifteen().toString());
        assertThat(saved.get(0).get("groupId").asString()).isEqualTo(saved.get(1).get("groupId").asString());
        assertThat(saved.get(0).get("receiptNumber").asLong()).isEqualTo(expected);
        assertThat(saved.get(1).get("receiptNumber").asLong()).isEqualTo(expected);
        assertThat(lastReceipt()).isEqualTo(expected);
        List<Map<String, Object>> rows = paymentsOf(plots.seven(), plots.fifteen());
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("group_id")).isEqualTo(rows.get(0).get("group_id"));
            assertThat(row.get("receipt_number")).isEqualTo(expected);
            assertThat(row.get("payer_member_id")).isEqualTo(plots.payer());
        });
        // the payer note is sealed per payment: each part opens its own
        assertThat(ownerJdbc().queryForList("select payer_note_enc from payment where plot_id in (?, ?)",
                byte[].class, plots.seven(), plots.fifteen())).hasSize(2).doesNotContainNull();
        awaitEntries(plots.seven(), "PAYMENT_RECORDED", 1);
        awaitEntries(plots.fifteen(), "PAYMENT_RECORDED", 1);

        UUID sevenPayment = UUID.fromString(saved.get(0).get("id").asString());
        UUID fifteenPayment = UUID.fromString(saved.get(1).get("id").asString());
        card(treasurer(), sevenPayment).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(expected))
                .andExpect(jsonPath("$.payer.memberId").value(plots.payer().toString()))
                .andExpect(jsonPath("$.groupParts.length()").value(1))
                .andExpect(jsonPath("$.groupParts[0].id").value(fifteenPayment.toString()))
                .andExpect(jsonPath("$.groupParts[0].plotId").value(plots.fifteen().toString()))
                .andExpect(jsonPath("$.groupParts[0].plotNumber").value(String.valueOf(base + 15)))
                .andExpect(jsonPath("$.groupParts[0].amount").value("50.00"))
                .andExpect(jsonPath("$.groupParts[0].reversed").value(false));
        card(treasurer(), fifteenPayment).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupParts[0].id").value(sevenPayment.toString()));
        send(treasurer(), get(PAYMENTS + "?plotId=" + plots.seven()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].groupSize").value(2))
                .andExpect(jsonPath("$.items[0].receiptNumber").value(expected));
    }

    @Test
    void aSinglePaymentIsAGroupOfOneInTheList() throws Exception {
        UUID plot = newPlot();
        save(treasurer(), cash(plot, "10.00")).andExpect(status().isCreated());

        send(treasurer(), get(PAYMENTS + "?plotId=" + plot), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].groupSize").value(1));
    }

    @Test
    void aBankSplitHasNoReceiptNumber() throws Exception {
        TwoPlots plots = twoPlots();
        Long before = lastReceipt();

        save(treasurer(), with(split("BANK", "200.00", plots.payer(), null), "bankReference", "ПД-77"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].receiptNumber").doesNotExist())
                .andExpect(jsonPath("$.payments[1].receiptNumber").doesNotExist());

        assertThat(lastReceipt()).isEqualTo(before);
        assertThat(ownerJdbc().queryForList("select bank_reference from payment where plot_id in (?, ?)",
                String.class, plots.seven(), plots.fifteen())).containsExactly("ПД-77", "ПД-77");
    }

    @Test
    void partsThatDoNotAddUpToTheAmountAreRefusedAndNothingIsSaved() throws Exception {
        TwoPlots plots = twoPlots();
        Long before = lastReceipt();

        save(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "100.00", null), part(plots.fifteen(), "50.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPLIT_TOTAL_MISMATCH"))
                .andExpect(jsonPath("$.errors[0].field").value("split.parts"))
                .andExpect(jsonPath("$.errors[0].code").value("SPLIT_TOTAL_MISMATCH"));
        // a preview shows the parts as typed with the problem instead (no digest: it cannot be saved)
        JsonNode preview = body(preview(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "150.00", null)))).andExpect(status().isOk()));
        assertThat(preview.get("valid").asBoolean()).isFalse();
        assertThat(preview.has("digest")).isFalse();
        assertThat(values(preview.get("problems"), "code")).containsExactly("SPLIT_TOTAL_MISMATCH");
        assertThat(preview.get("problems").get(0).has("plotId")).isFalse();
        assertThat(values(preview.get("parts"), "amount")).containsExactly("150.00");
        assertThat(values(preview.get("parts"), "automaticTotal")).containsExactly("0.00");

        assertThat(paymentsOf(plots.seven(), plots.fifteen())).isEmpty();
        assertThat(lastReceipt()).isEqualTo(before);
    }

    @Test
    void aPlotThatIsNotThePayersIsRefused() throws Exception {
        TwoPlots plots = twoPlots();
        UUID stranger = newPlot();
        tariffCharge(stranger, "MEMBERSHIP", "100.00", "2026-01");

        save(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "100.00", null), part(stranger, "100.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[1].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        save(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "100.00", null), part(plots.seven(), "100.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[1].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));

        assertThat(paymentsOf(plots.seven(), plots.fifteen(), stranger)).isEmpty();
    }

    @Test
    void archivedPlotsOfThePayerAreNotPartOfTheSplit() throws Exception {
        TwoPlots plots = twoPlots();
        UUID archived = newPlot(DEMO_TENANT, "PLOT", true);
        own(plots.payer(), archived);

        JsonNode preview = body(preview(treasurer(), split("CASH", "200.00", plots.payer(), null))
                .andExpect(status().isOk()));
        assertThat(values(preview.get("split").get("plots"), "plotId"))
                .containsExactly(plots.seven().toString(), plots.fifteen().toString());

        save(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "150.00", null), part(archived, "50.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[1].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    @Test
    void whenThePlotsOweNothingTheWholeAmountGoesToTheFirstPlotAsPrepayment() throws Exception {
        UUID payer = payer();
        UUID first = plotOf(payer, 3);
        UUID second = plotOf(payer, 20);

        JsonNode saved = body(save(treasurer(), split("CASH", "100.00", payer, null))
                .andExpect(status().isCreated()));

        assertThat(values(saved.get("parts"), "amount")).containsExactly("100.00", "0.00");
        assertThat(values(saved.get("parts"), "prepayment")).containsExactly("100.00", "0.00");
        assertThat(values(saved.get("split").get("plots"), "debt")).containsExactly("0.00", "0.00");
        // a part of 0.00 is shown, not saved
        assertThat(saved.get("payments")).hasSize(1);
        assertThat(saved.get("payments").get(0).get("plotId").asString()).isEqualTo(first.toString());
        assertThat(paymentCount(first)).isOne();
        assertThat(paymentCount(second)).isZero();
        assertThat(allocationCount(first)).isZero();
    }

    @Test
    void eachPartTakesItsOwnLinesCheckedAsForOnePlot() throws Exception {
        TwoPlots plots = twoPlots();

        save(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.seven(), "350.00", List.of(line(plots.sevenCharge(), "300.01"))),
                part(plots.fifteen(), "50.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_CHARGE"))
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[0].lines[0].amount"));
        save(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.fifteen(), "50.00", List.of(line(plots.fifteenCharge(), "60.00"))),
                part(plots.seven(), "350.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"))
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[0].lines"));
        save(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.seven(), "350.00", null),
                part(plots.fifteen(), "50.00", List.of(line(plots.sevenCharge(), "10.00"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[1].lines[0].chargeId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(paymentsOf(plots.seven(), plots.fifteen())).isEmpty();
        // a preview names the plot of the part with the problem
        JsonNode problems = body(preview(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.seven(), "350.00", null),
                part(plots.fifteen(), "50.00", List.of(line(plots.fifteenCharge(), "100.01"))))))
                .andExpect(status().isOk())).get("problems");
        assertThat(values(problems, "code")).containsExactly("ALLOCATION_EXCEEDS_PAYMENT", "ALLOCATION_EXCEEDS_CHARGE");
        assertThat(values(problems, "plotId")).containsOnly(plots.fifteen().toString());

        // the part of plot 7 places nothing by its lines: its 350.00 go on its 300.00 at once, 50.00 stay
        JsonNode parts = body(save(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.seven(), "350.00", List.of()),
                part(plots.fifteen(), "50.00", List.of(line(plots.fifteenCharge(), "50.00"))))))
                .andExpect(status().isCreated())).get("parts");
        assertThat(values(parts, "allocatedTotal")).containsExactly("300.00", "50.00");
        assertThat(values(parts, "automaticTotal")).containsExactly("300.00", "0.00");
        assertThat(values(parts, "prepayment")).containsExactly("50.00", "0.00");
        assertThat(allocated(plots.sevenCharge())).isEqualByComparingTo("300.00");
        assertThat(allocated(plots.fifteenCharge())).isEqualByComparingTo("50.00");
        assertThat(ownerJdbc().queryForObject("select origin from allocation where charge_id = ?", String.class,
                plots.sevenCharge())).isEqualTo("PREPAYMENT_AUTO");
    }

    @Test
    void withAutomaticAllocationOffTheRemainderOfAPartWaitsAsPrepayment() throws Exception {
        autoAllocation(false);
        TwoPlots plots = twoPlots();

        JsonNode parts = body(save(treasurer(), split("CASH", "400.00", plots.payer(), List.of(
                part(plots.seven(), "350.00", List.of()),
                part(plots.fifteen(), "50.00", List.of(line(plots.fifteenCharge(), "50.00"))))))
                .andExpect(status().isCreated())).get("parts");

        assertThat(values(parts, "allocatedTotal")).containsExactly("0.00", "50.00");
        assertThat(values(parts, "prepayment")).containsExactly("350.00", "0.00");
        assertThat(allocated(plots.sevenCharge())).isEqualByComparingTo("0.00");
    }

    @Test
    void theRequestNamesOnePlotOrASplitAndAPayerWithPlots() throws Exception {
        TwoPlots plots = twoPlots();

        save(treasurer(), with(split("CASH", "200.00", plots.payer(), null), "plotId", plots.seven().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("split"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(treasurer(), with(split("CASH", "200.00", plots.payer(), null), "lines", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines"));
        save(treasurer(), with(split("CASH", "200.00", plots.payer(), null), "payerMemberId",
                UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payerMemberId"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(treasurer(), split("CASH", "200.00", plots.payer(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.parts"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save(treasurer(), split("CASH", "200.00", plots.payer(),
                List.of(part(plots.seven(), "250.00", null), part(plots.fifteen(), "-50.00", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.parts[1].amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(treasurer(), split("CASH", "200.00", UUID.randomUUID(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.payerMemberId"))
                .andExpect(jsonPath("$.errors[0].code").value("MEMBER_NOT_FOUND"));
        preview(treasurer(), split("CASH", "200.00", payer(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("split.payerMemberId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));

        assertThat(paymentsOf(plots.seven(), plots.fifteen())).isEmpty();
    }

    @Test
    void aSplitSavedAfterTheChargesChangedIsRefusedAndWritesNothing() throws Exception {
        TwoPlots plots = twoPlots();
        String digest = body(preview(treasurer(), split("CASH", "200.00", plots.payer(), null))
                .andExpect(status().isOk())).get("digest").asString();
        tariffCharge(plots.fifteen(), "ELECTRICITY", "40.00", "2026-02");

        save(treasurer(), with(split("CASH", "200.00", plots.payer(), null), "digest", digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(paymentsOf(plots.seven(), plots.fifteen())).isEmpty();
    }

    @Test
    void theSplitNeedsPaymentsEnter() throws Exception {
        TwoPlots plots = twoPlots();

        preview(member(), split("CASH", "200.00", plots.payer(), null)).andExpect(status().isForbidden());
        save(operator(), split("CASH", "200.00", plots.payer(), null)).andExpect(status().isForbidden());
    }
}
