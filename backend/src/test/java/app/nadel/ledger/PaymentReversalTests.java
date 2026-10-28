package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reversing a payment end to end ({@code POST /payments/{id}/reverse}, feature 015 §2.5, worked example 9): every
 * allocation of the payment is released before the {@code PAYMENT_REVERSAL} row, so the charges it paid are owed
 * again; the reversal counts in the payment's own month while it is open, otherwise in the earliest open month; a cash
 * payment keeps its receipt number and is shown reversed; a part of a split group is reversed alone; a payment is
 * reversed once; a reason is required; only {@code PAYMENTS_REVERSE} may reverse; the plot's prepayment goes down by
 * what was left of the payment and never below zero.
 */
class PaymentReversalTests extends PaymentApiSupport {

    private static final String REASON = "Помилкова ділянка";

    /** Example 1 saved: membership 600.00 (January) and electricity 250.00 (February), cash 700.00 placed 600 + 100. */
    private record Paid(UUID plot, UUID membership, UUID electricity, UUID payment) {
    }

    private Paid example1Paid() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        return new Paid(plot, membership, electricity, saved(treasurer(), cash(plot, "700.00")));
    }

    private static JsonNode openLine(JsonNode part, UUID charge) {
        for (JsonNode line : part.get("lines")) {
            if (line.get("chargeId").asString().equals(charge.toString())) {
                return line;
            }
        }
        throw new AssertionError("The charge is not open");
    }

    private int reversalCount(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from payment where plot_id = ? and kind = "
                + "'PAYMENT_REVERSAL'", Integer.class, plot);
    }

    @Test
    void example9TheReversalReleasesEveryAllocationAndTheDebtsAreBack() throws Exception {
        Paid paid = example1Paid();
        Long receipt = ownerJdbc().queryForObject("select receipt_number from payment where id = ?", Long.class,
                paid.payment());
        card(chairman(), paid.payment()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reversible").value(true))
                .andExpect(jsonPath("$.reallocatable").value(true));
        card(treasurer(), paid.payment()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reversible").value(false))
                .andExpect(jsonPath("$.reallocatable").value(true));

        JsonNode card = body(reverse(chairman(), paid.payment(), "  " + REASON + " ")
                .andExpect(status().isCreated()));

        assertThat(card.get("id").asString()).isEqualTo(paid.payment().toString());
        assertThat(card.get("reversed").asBoolean()).isTrue();
        assertThat(card.get("reversible").asBoolean()).isFalse();
        assertThat(card.get("reallocatable").asBoolean()).isFalse();
        assertThat(card.get("reversedBy").get("reason").asString()).isEqualTo(REASON);
        assertThat(card.get("reversedBy").get("billingMonth").asString())
                .isEqualTo(kyivToday().toString().substring(0, 7));
        assertThat(card.get("receiptNumber").asLong()).isEqualTo(receipt);
        assertThat(card.get("allocated").asString()).isEqualTo("0.00");
        assertThat(card.get("prepayment").asString()).isEqualTo("0.00");
        List<String> amounts = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        card.get("allocations").forEach(row -> {
            amounts.add(row.get("amount").asString());
            if (row.has("releaseCause")) {
                causes.add(row.get("releaseCause").asString());
            }
        });
        assertThat(amounts).containsExactlyInAnyOrder("600.00", "100.00", "-600.00", "-100.00");
        assertThat(causes).containsExactly("PAYMENT_REVERSED", "PAYMENT_REVERSED");

        // the debts are back to 600.00 and 250.00
        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("0.00");
        assertThat(netAllocated(paid.electricity())).isEqualByComparingTo("0.00");
        JsonNode open = body(preview(treasurer(), cash(paid.plot(), "1.00")).andExpect(status().isOk()))
                .get("parts").get(0);
        assertThat(openLine(open, paid.membership()).get("open").asString()).isEqualTo("600.00");
        assertThat(openLine(open, paid.electricity()).get("open").asString()).isEqualTo("250.00");
        assertThat(open.get("debt").asString()).isEqualTo("850.00");

        Map<String, Object> reversal = ownerJdbc().queryForMap("select amount, received_on::text as received_on, "
                + "group_id, receipt_number, reason, source, created_by from payment where reverses_payment_id = ?",
                paid.payment());
        assertThat((BigDecimal) reversal.get("amount")).isEqualByComparingTo("-700.00");
        assertThat(reversal).containsEntry("received_on", kyivToday().toString())
                .containsEntry("group_id", paid.payment()).containsEntry("reason", REASON)
                .containsEntry("source", "CASH").containsEntry("created_by", demoMember("chairman@demo.local"));
        assertThat(reversal.get("receipt_number")).isNull();
        assertThat(ownerJdbc().queryForList("select distinct created_by from allocation where amount < 0 "
                + "and payment_id = ?", UUID.class, paid.payment()))
                .containsExactly(demoMember("chairman@demo.local"));
    }

    @Test
    void aPaymentIsReversedOnceAndAReversalRowIsNotReversed() throws Exception {
        Paid paid = example1Paid();
        reverse(chairman(), paid.payment(), REASON).andExpect(status().isCreated());

        reverse(chairman(), paid.payment(), "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));
        UUID reversalRow = ownerJdbc().queryForObject("select id from payment where reverses_payment_id = ?",
                UUID.class, paid.payment());
        reverse(chairman(), reversalRow, "Сторно сторно").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));

        assertThat(reversalCount(paid.plot())).isOne();
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where plot_id = ? and amount < 0",
                Integer.class, paid.plot())).isEqualTo(2);
    }

    @Test
    void aReasonIsRequired() throws Exception {
        Paid paid = example1Paid();

        reverse(chairman(), paid.payment(), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        reverse(chairman(), paid.payment(), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        reverse(chairman(), paid.payment(), "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        reverse(chairman(), paid.payment(), "п".repeat(501)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));

        assertThat(reversalCount(paid.plot())).isZero();
        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("600.00");
    }

    @Test
    void onlyPaymentsReverseMayReverseAndOnlyPaymentsOfTheCooperative() throws Exception {
        Paid paid = example1Paid();

        reverse(treasurer(), paid.payment(), REASON).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        reverse(member(), paid.payment(), REASON).andExpect(status().isForbidden());
        reverse(customRoleToken("PAYMENTS_VIEW", "PAYMENTS_ENTER"), paid.payment(), REASON)
                .andExpect(status().isForbidden());
        reverse(operator(), paid.payment(), REASON).andExpect(status().isForbidden());
        reverse(otherChairman(), paid.payment(), REASON).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        reverse(chairman(), UUID.randomUUID(), REASON).andExpect(status().isNotFound());
        assertThat(reversalCount(paid.plot())).isZero();

        reverse(customRoleToken("PAYMENTS_VIEW", "PAYMENTS_REVERSE"), paid.payment(), REASON)
                .andExpect(status().isCreated());
        assertThat(reversalCount(paid.plot())).isOne();
    }

    @Test
    void aReversedCashPaymentKeepsItsReceiptAndIsShownReversed() throws Exception {
        Paid paid = example1Paid();
        long receipt = ownerJdbc().queryForObject("select receipt_number from payment where id = ?", Long.class,
                paid.payment());

        reverse(chairman(), paid.payment(), REASON).andExpect(status().isCreated());
        UUID next = saved(treasurer(), cash(paid.plot(), "10.00"));

        JsonNode list = body(send(treasurer(), get(PAYMENTS + "?plotId=" + paid.plot()), null)
                .andExpect(status().isOk()));
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        list.get("items").forEach(item -> byId.put(item.get("id").asString(), item));
        assertThat(byId).hasSize(2);
        JsonNode reversed = byId.get(paid.payment().toString());
        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        assertThat(reversed.get("receiptNumber").asLong()).isEqualTo(receipt);
        assertThat(reversed.get("allocated").asString()).isEqualTo("0.00");
        assertThat(reversed.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(byId.get(next.toString()).get("receiptNumber").asLong()).isEqualTo(receipt + 1);
        assertThat(byId.get(next.toString()).get("reversed").asBoolean()).isFalse();
        card(treasurer(), paid.payment()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reversed").value(true))
                .andExpect(jsonPath("$.receiptNumber").value(receipt))
                .andExpect(jsonPath("$.reversedBy.reason").value(REASON));
    }

    @Test
    void aPartOfASplitIsReversedAloneAndTheOtherPartStays() throws Exception {
        UUID payer = newMember(DEMO_TENANT, "payer-" + UUID.randomUUID() + "@reversal.test");
        UUID first = newPlot();
        UUID second = newPlot();
        own(payer, first);
        own(payer, second);
        UUID firstCharge = tariffCharge(first, "MEMBERSHIP", "100.00", "2026-01");
        UUID secondCharge = tariffCharge(second, "MEMBERSHIP", "100.00", "2026-01");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("source", "CASH");
        split.put("amount", "200.00");
        split.put("receivedOn", kyivToday().toString());
        split.put("split", Map.of("payerMemberId", payer.toString()));
        JsonNode saved = body(save(treasurer(), split).andExpect(status().isCreated())).get("payments");
        assertThat(saved).hasSize(2);
        UUID reversedPart = UUID.fromString(saved.get(0).get("id").asString());
        UUID otherPart = UUID.fromString(saved.get(1).get("id").asString());
        UUID reversedPlot = UUID.fromString(saved.get(0).get("plotId").asString());

        JsonNode card = body(reverse(chairman(), reversedPart, REASON).andExpect(status().isCreated()));

        assertThat(card.get("groupParts").get(0).get("id").asString()).isEqualTo(otherPart.toString());
        assertThat(card.get("groupParts").get(0).get("reversed").asBoolean()).isFalse();
        card(chairman(), otherPart).andExpect(status().isOk())
                .andExpect(jsonPath("$.reversed").value(false))
                .andExpect(jsonPath("$.reversible").value(true))
                .andExpect(jsonPath("$.allocated").value("100.00"))
                .andExpect(jsonPath("$.groupParts[0].reversed").value(true));
        UUID reversedCharge = reversedPlot.equals(first) ? firstCharge : secondCharge;
        UUID keptCharge = reversedPlot.equals(first) ? secondCharge : firstCharge;
        assertThat(netAllocated(reversedCharge)).isEqualByComparingTo("0.00");
        assertThat(netAllocated(keptCharge)).isEqualByComparingTo("100.00");
        assertThat(reversalCount(first) + reversalCount(second)).isOne();
    }

    @Test
    void theReversalCountsInThePaymentsMonthWhileItIsOpenOtherwiseInTheEarliestOpenMonth() throws Exception {
        UUID plot = newPlot();
        UUID january = month(DEMO_TENANT, "2026-01");
        UUID inOpenMonth = saved(treasurer(), payment(plot, "CASH", "40.00", "2026-01-20", null));
        UUID inClosedMonth = saved(treasurer(), payment(plot, "BANK", "60.00", "2026-01-25", null));

        reverse(chairman(), inOpenMonth, REASON).andExpect(status().isCreated())
                .andExpect(jsonPath("$.billingMonth").value("2026-01"))
                .andExpect(jsonPath("$.reversedBy.billingMonth").value("2026-01"));

        closedMonth(DEMO_TENANT, "2026-01");
        reverse(chairman(), inClosedMonth, REASON).andExpect(status().isCreated())
                .andExpect(jsonPath("$.billingMonth").value("2026-01"))
                .andExpect(jsonPath("$.reversedBy.billingMonth").value("2026-02"));
        assertThat(ownerJdbc().queryForObject("select received_on::text from payment where reverses_payment_id = ?",
                String.class, inClosedMonth)).isEqualTo("2026-01-25");
        // the closed month keeps its received total: only the reversal of its open time counts in it
        assertThat(ownerJdbc().queryForObject("select sum(amount) from payment where billing_month_id = ?",
                BigDecimal.class, january)).isEqualByComparingTo("60.00");
    }

    @Test
    void aPaymentOfAnArchivedPlotCanStillBeReversed() throws Exception {
        Paid paid = example1Paid();
        ownerJdbc().update("update plot set archived_at = now() where id = ?", paid.plot());

        reverse(chairman(), paid.payment(), REASON).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversed").value(true));

        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("0.00");
    }

    @Test
    void thePrepaymentGoesDownByWhatWasLeftOfThePaymentAndNeverBelowZero() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        // example 2: 1000.00 pays 850.00 and leaves 150.00 as prepayment; a second payment of 50.00 adds to it
        UUID large = saved(treasurer(), cash(plot, "1000.00"));
        UUID small = saved(treasurer(), payment(plot, "BANK", "50.00", kyivToday().toString(), null));
        awaitPrepayment(plot, "200.00");

        reverse(chairman(), large, REASON).andExpect(status().isCreated());
        // its 150.00 left is gone with it, and the 850.00 it paid is owed again
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("50.00");
        awaitPrepayment(plot, "50.00");
        assertThat(ownerJdbc().queryForObject("select debt from plot_status where plot_id = ?", BigDecimal.class,
                plot)).isEqualByComparingTo("850.00");

        reverse(chairman(), small, REASON).andExpect(status().isCreated());
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("0.00");
        awaitPrepayment(plot, "0.00");
    }

    private void awaitPrepayment(UUID plot, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ownerJdbc().queryForObject(
                "select prepayment from plot_status where plot_id = ?", BigDecimal.class, plot))
                .isEqualByComparingTo(expected));
    }
}
