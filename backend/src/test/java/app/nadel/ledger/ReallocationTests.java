package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reallocating a saved payment end to end ({@code POST /payments/{id}/reallocate?preview=}, feature 015 §2.6): the
 * preview shows what the payment places now and a proposal over the open charges as if that were taken back; a save
 * releases every live allocation of the payment ({@code REALLOCATED}) and writes the new lines (origin
 * {@code REALLOCATION}) in one transaction; the card lists both. A stale digest, a reversed payment and lines that
 * exceed the payment or a charge are refused and write nothing.
 */
class ReallocationTests extends PaymentApiSupport {

    /** Example 1 saved: membership 600.00 (January) and electricity 250.00 (February), cash 700.00 placed 600 + 100. */
    private record Paid(UUID plot, UUID membership, UUID electricity, UUID payment) {
    }

    private Paid example1Paid() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        return new Paid(plot, membership, electricity, saved(treasurer(), cash(plot, "700.00")));
    }

    private static JsonNode line(JsonNode part, UUID charge) {
        for (JsonNode line : part.get("lines")) {
            if (line.get("chargeId").asString().equals(charge.toString())) {
                return line;
            }
        }
        throw new AssertionError("The charge is not open");
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.has(field) ? item.get(field).asString() : null));
        return values;
    }

    @Test
    void theFs26ExampleMovesTheMoneyAndTheCardListsTheReleasedAndTheNewAllocations() throws Exception {
        Paid paid = example1Paid();
        List<Map<String, Object>> lines = List.of(line(paid.electricity(), "250.00"),
                line(paid.membership(), "450.00"));

        JsonNode preview = body(reallocate(treasurer(), paid.payment(), true, lines(lines, null))
                .andExpect(status().isOk()));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("paymentId").asString()).isEqualTo(paid.payment().toString());
        assertThat(values(preview.get("current"), "chargeId"))
                .containsExactlyInAnyOrder(paid.membership().toString(), paid.electricity().toString());
        assertThat(values(preview.get("current"), "amount")).containsExactlyInAnyOrder("600.00", "100.00");
        JsonNode proposal = preview.get("proposal");
        assertThat(proposal.get("amount").asString()).isEqualTo("700.00");
        assertThat(line(proposal, paid.membership()).get("open").asString()).isEqualTo("600.00");
        assertThat(line(proposal, paid.electricity()).get("open").asString()).isEqualTo("250.00");
        assertThat(line(proposal, paid.membership()).get("proposed").asString()).isEqualTo("450.00");
        assertThat(line(proposal, paid.electricity()).get("proposed").asString()).isEqualTo("250.00");
        assertThat(proposal.get("allocatedTotal").asString()).isEqualTo("700.00");
        assertThat(proposal.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(allocationCount(paid.plot())).isEqualTo(2);

        reallocate(treasurer(), paid.payment(), false, lines(lines, preview.get("digest").asString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview").value(false));

        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("450.00");
        assertThat(netAllocated(paid.electricity())).isEqualByComparingTo("250.00");
        // the account: membership open 150.00, electricity paid
        JsonNode open = body(preview(treasurer(), cash(paid.plot(), "1.00")).andExpect(status().isOk()))
                .get("parts").get(0);
        assertThat(values(open.get("lines"), "chargeId")).containsExactly(paid.membership().toString());
        assertThat(line(open, paid.membership()).get("open").asString()).isEqualTo("150.00");

        JsonNode card = body(card(treasurer(), paid.payment()).andExpect(status().isOk()));
        assertThat(card.get("allocated").asString()).isEqualTo("700.00");
        assertThat(card.get("allocations")).hasSize(6);
        List<String> rows = new ArrayList<>();
        card.get("allocations").forEach(row -> rows.add((row.has("origin") ? row.get("origin").asString()
                : row.get("releaseCause").asString()) + " " + row.get("amount").asString()));
        assertThat(rows).containsExactlyInAnyOrder("ENTRY 600.00", "ENTRY 100.00", "REALLOCATED -600.00",
                "REALLOCATED -100.00", "REALLOCATION 250.00", "REALLOCATION 450.00");
        assertThat(ownerJdbc().queryForList("select distinct created_by from allocation where plot_id = ? "
                + "and (origin = 'REALLOCATION' or release_cause = 'REALLOCATED')", UUID.class, paid.plot()))
                .containsExactly(demoMember("treasurer@demo.local"));
    }

    @Test
    void withoutLinesTheProposalAsIfReleasedIsSaved() throws Exception {
        Paid paid = example1Paid();
        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(paid.electricity(), "250.00")), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.prepayment").value("450.00"));
        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("0.00");

        JsonNode saved = body(reallocate(treasurer(), paid.payment(), false, lines(null, null))
                .andExpect(status().isCreated()));

        assertThat(values(saved.get("current"), "amount")).containsExactly("250.00");
        assertThat(line(saved.get("proposal"), paid.membership()).get("proposed").asString()).isEqualTo("600.00");
        assertThat(line(saved.get("proposal"), paid.electricity()).get("proposed").asString()).isEqualTo("100.00");
        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("600.00");
        assertThat(netAllocated(paid.electricity())).isEqualByComparingTo("100.00");
    }

    @Test
    void aSaveWithTheDigestOfAnOutdatedPreviewIsRefusedAndWritesNothing() throws Exception {
        Paid paid = example1Paid();
        String digest = body(reallocate(treasurer(), paid.payment(), true, lines(null, null))
                .andExpect(status().isOk())).get("digest").asString();

        // another payment places money on the plot's electricity after the preview
        save(treasurer(), payment(paid.plot(), "BANK", "50.00", kyivToday().toString(), null))
                .andExpect(status().isCreated());
        int allocations = allocationCount(paid.plot());

        // lines that still fit the plot as it is now: only the digest tells the save is stale
        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(paid.electricity(), "100.00")), digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(allocationCount(paid.plot())).isEqualTo(allocations);
        String fresh = body(reallocate(treasurer(), paid.payment(), true, lines(null, null))).get("digest")
                .asString();
        assertThat(fresh).isNotEqualTo(digest);
        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(paid.electricity(), "200.00")), fresh))
                .andExpect(status().isCreated());
        assertThat(netAllocated(paid.electricity())).isEqualByComparingTo("250.00");
    }

    @Test
    void aReversedPaymentAndAReversalRowAreNotReallocated() throws Exception {
        Paid paid = example1Paid();
        reverse(chairman(), paid.payment(), "Помилкова ділянка").andExpect(status().isCreated());
        int allocations = allocationCount(paid.plot());

        reallocate(treasurer(), paid.payment(), true, lines(null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));
        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(paid.membership(), "100.00")), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));
        UUID reversalRow = ownerJdbc().queryForObject("select id from payment where reverses_payment_id = ?",
                UUID.class, paid.payment());
        reallocate(treasurer(), reversalRow, false, lines(null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));

        assertThat(allocationCount(paid.plot())).isEqualTo(allocations);
    }

    @Test
    void linesAboveThePaymentOrAChargeOrNotOfThePlotAreRefusedAndNothingIsWritten() throws Exception {
        Paid paid = example1Paid();
        Paid other = example1Paid();

        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(paid.membership(), "600.00"),
                line(paid.electricity(), "250.00")), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));
        reallocate(treasurer(), paid.payment(), true, lines(List.of(line(paid.electricity(), "250.01")), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_CHARGE"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].amount"));
        reallocate(treasurer(), paid.payment(), false, lines(List.of(line(other.membership(), "10.00"),
                line(paid.membership(), "0.00")), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].chargeId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[1].field").value("lines[1].amount"))
                .andExpect(jsonPath("$.errors[1].code").value("AMOUNT_NOT_POSITIVE"));

        assertThat(allocationCount(paid.plot())).isEqualTo(2);
        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("600.00");
    }

    @Test
    void anEmptyListTakesEverythingBackAsPrepaymentOfThePayment() throws Exception {
        Paid paid = example1Paid();

        reallocate(treasurer(), paid.payment(), false, lines(List.of(), null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.allocatedTotal").value("0.00"))
                .andExpect(jsonPath("$.proposal.prepayment").value("700.00"));

        assertThat(netAllocated(paid.membership())).isEqualByComparingTo("0.00");
        assertThat(netAllocated(paid.electricity())).isEqualByComparingTo("0.00");
        assertThat(ledgerPrepayment(paid.plot())).isEqualByComparingTo("700.00");
    }

    @Test
    void reallocationNeedsPaymentsEnterAndAPaymentOfTheCooperative() throws Exception {
        Paid paid = example1Paid();

        reallocate(member(), paid.payment(), true, lines(null, null)).andExpect(status().isForbidden());
        reallocate(customRoleToken("PAYMENTS_VIEW"), paid.payment(), true, lines(null, null))
                .andExpect(status().isForbidden());
        reallocate(operator(), paid.payment(), true, lines(null, null)).andExpect(status().isForbidden());
        reallocate(otherChairman(), paid.payment(), true, lines(null, null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        reallocate(treasurer(), UUID.randomUUID(), true, lines(null, null)).andExpect(status().isNotFound());
        reallocate(customRoleToken("PAYMENTS_ENTER"), paid.payment(), true, lines(null, null))
                .andExpect(status().isOk());
        assertThat(allocationCount(paid.plot())).isEqualTo(2);
    }
}
