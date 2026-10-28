package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Allocating a plot's prepayment by hand end to end ({@code POST /plots/{id}/prepayment/allocate?preview=}, feature
 * 015 §2.6, worked example 5): the proposal over the plot's open charges up to the prepayment, the caller's lines,
 * each line's money taken from the payments that still have money left, oldest first (origin
 * {@code PREPAYMENT_MANUAL}, by the actor). Meant for automatic allocation off (the tests run with it off), it works
 * while it is on too. Nothing to allocate is 409
 * {@code NOTHING_TO_ALLOCATE}; lines above the prepayment are 400 {@code ALLOCATION_EXCEEDS_PAYMENT}.
 */
class PrepaymentAllocationTests extends PaymentApiSupport {

    private static JsonNode line(JsonNode part, UUID charge) {
        for (JsonNode line : part.get("lines")) {
            if (line.get("chargeId").asString().equals(charge.toString())) {
                return line;
            }
        }
        throw new AssertionError("The charge is not open");
    }

    /**
     * «Розподілити аванс» is for automatic allocation off (owner decision of 28.10.2026: while it is on, the remainder
     * of a payment is placed at once and no prepayment waits for an open charge); a test that shows it works while
     * on switches it on itself.
     */
    @BeforeEach
    void automaticAllocationOff() {
        autoAllocation(false);
    }

    @Test
    void example5ThenTheTreasurerAllocatesThePrepaymentAndLandTaxIsPaid() throws Exception {
        // example 2: 1000.00 pays membership 600.00 and electricity 250.00, 150.00 stays as prepayment
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        UUID payment = saved(treasurer(), cash(plot, "1000.00"));
        // example 5: land tax 120.00 is booked with automatic allocation off: it stays open
        UUID landTax = tariffCharge(plot, "LAND_TAX", "120.00", "2026-03");
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("150.00");

        JsonNode preview = body(allocatePrepayment(treasurer(), plot, true, lines(null, null))
                .andExpect(status().isOk()));
        JsonNode proposal = preview.get("proposal");
        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(proposal.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(proposal.get("amount").asString()).isEqualTo("150.00");
        assertThat(proposal.get("lines")).hasSize(1);
        assertThat(line(proposal, landTax).get("open").asString()).isEqualTo("120.00");
        assertThat(line(proposal, landTax).get("proposed").asString()).isEqualTo("120.00");
        assertThat(proposal.get("allocatedTotal").asString()).isEqualTo("120.00");
        assertThat(proposal.get("prepayment").asString()).isEqualTo("30.00");
        assertThat(netAllocated(landTax)).isEqualByComparingTo("0.00");

        allocatePrepayment(treasurer(), plot, false, lines(null, preview.get("digest").asString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview").value(false))
                .andExpect(jsonPath("$.proposal.prepayment").value("30.00"));

        assertThat(netAllocated(landTax)).isEqualByComparingTo("120.00");
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("30.00");
        Map<String, Object> row = ownerJdbc().queryForMap("select payment_id, origin, created_by from allocation "
                + "where charge_id = ?", landTax);
        assertThat(row).containsEntry("payment_id", payment).containsEntry("origin", "PREPAYMENT_MANUAL")
                .containsEntry("created_by", demoMember("treasurer@demo.local"));
        card(treasurer(), payment).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocated").value("970.00"))
                .andExpect(jsonPath("$.prepayment").value("30.00"));
    }

    @Test
    void aPrepaymentOfTwoPaymentsIsTakenFromTheOldestPaymentFirst() throws Exception {
        // it works with automatic allocation on too: the money was paid before the charges were booked
        autoAllocation(true);
        UUID plot = newPlot();
        UUID newer = saved(treasurer(), payment(plot, "BANK", "100.00", "2026-04-01", null));
        UUID older = saved(treasurer(), payment(plot, "CASH", "50.00", "2026-03-01", null));
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "90.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "40.00", "2026-02");

        allocatePrepayment(treasurer(), plot, false, lines(List.of(line(electricity, "40.00"),
                line(membership, "80.00")), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.allocatedTotal").value("120.00"))
                .andExpect(jsonPath("$.proposal.prepayment").value("30.00"));

        // the older payment (50.00) gives its money first: electricity 40.00, then 10.00 of membership
        assertThat(ownerJdbc().queryForObject("select sum(amount) from allocation where payment_id = ?",
                BigDecimal.class, older)).isEqualByComparingTo("50.00");
        assertThat(ownerJdbc().queryForObject("select sum(amount) from allocation where payment_id = ?",
                BigDecimal.class, newer)).isEqualByComparingTo("70.00");
        assertThat(ownerJdbc().queryForObject("select amount from allocation where payment_id = ? and charge_id = ?",
                BigDecimal.class, older, electricity)).isEqualByComparingTo("40.00");
        assertThat(ownerJdbc().queryForObject("select amount from allocation where payment_id = ? and charge_id = ?",
                BigDecimal.class, older, membership)).isEqualByComparingTo("10.00");
        assertThat(ownerJdbc().queryForObject("select amount from allocation where payment_id = ? and charge_id = ?",
                BigDecimal.class, newer, membership)).isEqualByComparingTo("70.00");
        assertThat(netAllocated(membership)).isEqualByComparingTo("80.00");
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("30.00");
    }

    @Test
    void nothingToAllocateIsAConflictAndAnEmptyListIsInvalid() throws Exception {
        UUID unpaid = newPlot();
        tariffCharge(unpaid, "MEMBERSHIP", "100.00", "2026-01");
        UUID prepaidWithoutCharges = newPlot();
        saved(treasurer(), cash(prepaidWithoutCharges, "100.00"));
        UUID prepaid = newPlot();
        UUID membership = tariffCharge(prepaid, "MEMBERSHIP", "100.00", "2026-01");
        saved(treasurer(), payment(prepaid, "CASH", "60.00", kyivToday().toString(), List.of()));

        allocatePrepayment(treasurer(), unpaid, true, lines(null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_ALLOCATE"));
        allocatePrepayment(treasurer(), prepaidWithoutCharges, false, lines(null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_ALLOCATE"));
        allocatePrepayment(treasurer(), prepaid, false, lines(List.of(), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lines"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        allocatePrepayment(treasurer(), prepaid, false, lines(List.of(line(membership, "60.01")), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));

        assertThat(allocationCount(unpaid) + allocationCount(prepaidWithoutCharges) + allocationCount(prepaid))
                .isZero();
    }

    @Test
    void aSaveWithTheDigestOfAnOutdatedPreviewIsRefused() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "100.00", "2026-01");
        saved(treasurer(), payment(plot, "CASH", "60.00", kyivToday().toString(), List.of()));
        String digest = body(allocatePrepayment(treasurer(), plot, true, lines(null, null))
                .andExpect(status().isOk())).get("digest").asString();

        // another payment adds to the prepayment after the preview
        saved(treasurer(), payment(plot, "BANK", "10.00", kyivToday().toString(), List.of()));

        allocatePrepayment(treasurer(), plot, false, lines(null, digest)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));
        assertThat(allocationCount(plot)).isZero();
        allocatePrepayment(treasurer(), plot, false, lines(null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.allocatedTotal").value("70.00"));
    }

    private JsonNode account(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
    }

    private boolean offered(String bearer, UUID plot, UUID payment) throws Exception {
        boolean onAccount = account(bearer, plot).get("prepaymentAllocatable").asBoolean();
        boolean onCard = body(card(bearer, payment).andExpect(status().isOk())).get("prepaymentAllocatable")
                .asBoolean();
        assertThat(onCard).as("the card says what the account says").isEqualTo(onAccount);
        return onAccount;
    }

    @Test
    void allocatePrepaymentIsOfferedOnlyWithPaymentsEnterTheSettingOffAPrepaymentAndAnOpenCharge() throws Exception {
        UUID plot = newPlot();
        UUID payment = saved(treasurer(), payment(plot, "CASH", "60.00", kyivToday().toString(), null));
        UUID onlyPrepayment = newPlot();
        UUID itsPayment = saved(treasurer(), payment(onlyPrepayment, "CASH", "10.00", kyivToday().toString(), null));
        // booked after the payments (as the owner, no event): 60.00 wait while 100.00 are open
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "100.00", "2026-01");

        assertThat(offered(treasurer(), plot, payment)).isTrue();
        assertThat(offered(chairman(), plot, payment)).isTrue();
        // without PAYMENTS_ENTER: viewing only
        assertThat(offered(customRoleToken("PAYMENTS_VIEW", "CHARGES_VIEW"), plot, payment)).isFalse();
        // a prepayment but nothing open
        assertThat(offered(treasurer(), onlyPrepayment, itsPayment)).isFalse();
        // automatic allocation on: not offered (no prepayment waits for an open charge then)
        autoAllocation(true);
        assertThat(offered(treasurer(), plot, payment)).isFalse();
        autoAllocation(false);
        // the prepayment placed: nothing left to offer
        allocatePrepayment(treasurer(), plot, false, lines(List.of(line(membership, "60.00")), null))
                .andExpect(status().isCreated());
        assertThat(offered(treasurer(), plot, payment)).isFalse();
        // a member who owns the plot sees the account, never the offer
        own(demoMember("member@demo.local"), plot);
        assertThat(account(member(), plot).get("prepaymentAllocatable").asBoolean()).isFalse();
    }

    @Test
    void allocatingAPrepaymentNeedsPaymentsEnterAndAPlotOfTheCooperative() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "100.00", "2026-01");
        saved(treasurer(), payment(plot, "CASH", "60.00", kyivToday().toString(), List.of()));
        UUID foreign = newPlot(OTHER_TENANT, "PLOT", false);

        allocatePrepayment(member(), plot, true, lines(null, null)).andExpect(status().isForbidden());
        allocatePrepayment(customRoleToken("PAYMENTS_VIEW"), plot, true, lines(null, null))
                .andExpect(status().isForbidden());
        allocatePrepayment(operator(), plot, true, lines(null, null)).andExpect(status().isForbidden());
        allocatePrepayment(treasurer(), foreign, true, lines(null, null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        allocatePrepayment(treasurer(), UUID.randomUUID(), false, lines(null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        allocatePrepayment(customRoleToken("PAYMENTS_ENTER"), plot, true, lines(null, null))
                .andExpect(status().isOk());
        assertThat(allocationCount(plot)).isZero();

        allocatePrepayment(chairman(), plot, false, lines(null, null)).andExpect(status().isCreated());
        assertThat(allocationCount(plot)).isOne();
    }
}
