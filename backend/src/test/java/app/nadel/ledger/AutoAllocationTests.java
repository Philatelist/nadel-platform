package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The automatic allocation of prepayment (feature 015 §2.4, FS 2.7, worked examples 4 and 5): after charges are booked
 * for a plot (a month run, an opening balance, ...: {@link ChargesBooked}), its prepayment is placed on its open charges
 * in the planner's order, after the commit and in a transaction of its own, as allocations of origin
 * {@code PREPAYMENT_AUTO} by the system, taken from the payments with money left, oldest first; only while the
 * cooperative's setting {@code autoAllocatePrepayment} is on. A redelivered event writes nothing. A payment entry
 * places its own remainder at once while the setting is on (owner decision of 28.10.2026); only what no open charge
 * can take waits for new charges. Each test runs in a cooperative of its own and waits for the listeners.
 */
class AutoAllocationTests extends FreshPaymentSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** Example 2: membership 600.00 and electricity 250.00 paid by 1000.00, 150.00 left as prepayment. */
    private UUID example2(UUID plot) throws Exception {
        charge(plot, "MEMBERSHIP", "600.00", "2026-01");
        charge(plot, "ELECTRICITY", "250.00", "2026-02");
        UUID payment = pay(plot, "1000.00");
        awaitListeners();
        assertThat(prepayment(plot)).isEqualByComparingTo("150.00");
        return payment;
    }

    /** A land tax tariff of 120.00 a month for every plot, booked by the month run of the current month. */
    private void landTaxBookedByTheMonthRun() throws Exception {
        tariff("LAND_TAX", "Земельний податок", "PER_PLOT", "MONTHLY", "120.0000", "2020-01-01");
        send(chairman, post(RUN.formatted(ym(0), false)), null).andExpect(status().isOk());
        awaitListeners();
    }

    @Test
    void example4LandTaxIsPaidFromThePrepaymentAnd30Remains() throws Exception {
        UUID plot = plot("9", "600");
        UUID payment = example2(plot);

        landTaxBookedByTheMonthRun();

        UUID landTax = bookedCharge(plot, "LAND_TAX", ym(0));
        List<Map<String, Object>> auto = allocations(plot, "PREPAYMENT_AUTO");
        assertThat(auto).hasSize(1);
        assertThat((BigDecimal) auto.get(0).get("amount")).isEqualByComparingTo("120.00");
        // by the system, from the payment that has the money
        assertThat(auto.get(0)).containsEntry("charge_id", landTax).containsEntry("payment_id", payment);
        assertThat(auto.get(0).get("created_by")).isNull();
        assertThat(open(landTax)).isEqualByComparingTo("0.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("30.00");
        assertThat(owner().queryForMap("select debt, prepayment from plot_status where plot_id = ?", plot))
                .satisfies(row -> {
                    assertThat((BigDecimal) row.get("debt")).isEqualByComparingTo("0.00");
                    assertThat((BigDecimal) row.get("prepayment")).isEqualByComparingTo("30.00");
                });
        invariantsHold(plot);
    }

    @Test
    void example5WithAutomaticAllocationOffTheLandTaxStaysOpen() throws Exception {
        autoAllocation(false);
        UUID plot = plot("9", "600");
        example2(plot);

        landTaxBookedByTheMonthRun();

        UUID landTax = bookedCharge(plot, "LAND_TAX", ym(0));
        assertThat(allocations(plot, "PREPAYMENT_AUTO")).isEmpty();
        assertThat(open(landTax)).isEqualByComparingTo("120.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("150.00");
    }

    @Test
    void afterAMonthRunEveryPlotWithAPrepaymentIsPaidAndOthersAreLeftAlone() throws Exception {
        UUID prepaid = plot("1", "600");
        UUID partly = plot("2", "600");
        UUID unpaid = plot("3", "600");
        // money paid before anything was charged: all of it is prepayment
        UUID older = pay(prepaid, "100.00");
        UUID newer = pay(prepaid, "100.00");
        pay(partly, "50.00");
        awaitListeners();

        landTaxBookedByTheMonthRun();

        // 200.00 of two payments: 120.00 taken from the older payment first
        List<Map<String, Object>> auto = allocations(prepaid, "PREPAYMENT_AUTO");
        assertThat(auto).hasSize(2);
        assertThat(auto).extracting(row -> row.get("payment_id") + " " + ((BigDecimal) row.get("amount"))
                .toPlainString()).containsExactlyInAnyOrder(older + " 100.00", newer + " 20.00");
        assertThat(prepayment(prepaid)).isEqualByComparingTo("80.00");
        // 50.00 placed on the 120.00: 70.00 still open
        assertThat(open(bookedCharge(partly, "LAND_TAX", ym(0)))).isEqualByComparingTo("70.00");
        assertThat(prepayment(partly)).isEqualByComparingTo("0.00");
        assertThat(allocationCount(unpaid)).isZero();
        invariantsHold(prepaid);
        invariantsHold(partly);
    }

    @Test
    void afterAnOpeningBalanceThePrepaymentIsPlacedOnIt() throws Exception {
        UUID plot = plot("4", "600");
        pay(plot, "500.00");
        awaitListeners();

        send(chairman, post(OPENING.formatted(plot)), Map.of("chargeTypeId", typeId("MEMBERSHIP").toString(),
                "amount", "1200.00", "date", "2025-12-31", "reason", "Борг за 2025 рік"))
                .andExpect(status().isCreated());
        awaitListeners();

        UUID opening = owner().queryForObject("select id from charge where plot_id = ? and kind = 'OPENING'",
                UUID.class, plot);
        assertThat(allocations(plot, "PREPAYMENT_AUTO")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("charge_id", opening));
        assertThat(open(opening)).isEqualByComparingTo("700.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("0.00");
    }

    @Test
    void aRedeliveredChargesBookedWritesNothing() throws Exception {
        UUID plot = plot("9", "600");
        example2(plot);
        landTaxBookedByTheMonthRun();
        int before = allocationCount(plot);

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> events.publishEvent(new ChargesBooked(tenant, Set.of(plot))));
        awaitListeners();

        assertThat(allocationCount(plot)).isEqualTo(before);
        assertThat(prepayment(plot)).isEqualByComparingTo("30.00");
        invariantsHold(plot);
    }

    @Test
    void aPaymentEntryPlacesItsOwnRemainderAtOnceAndOnlyWhatTheDebtsCannotTakeWaits() throws Exception {
        UUID plot = plot("9", "600");
        UUID membership = charge(plot, "MEMBERSHIP", "600.00", "2026-01");

        // the treasurer places 300.00 of 700.00: the next 300.00 go on the membership at once, by the system, in the
        // entry's transaction; 100.00 are left with nothing open and wait for new charges
        UUID payment = pay(plot, "700.00", List.of(line(membership, "300.00")));
        awaitListeners();

        assertThat(allocations(plot, "PREPAYMENT_AUTO")).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("300.00");
            assertThat(row).containsEntry("payment_id", payment).containsEntry("charge_id", membership);
            assertThat(row.get("created_by")).isNull();
        });
        assertThat(allocationCount(plot)).isEqualTo(2);
        assertThat(open(membership)).isEqualByComparingTo("0.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("100.00");
        invariantsHold(plot);
    }

    @Test
    void withTheSettingOffTheRemainderOfAPaymentEntryWaitsAsPrepayment() throws Exception {
        autoAllocation(false);
        UUID plot = plot("9", "600");
        UUID membership = charge(plot, "MEMBERSHIP", "600.00", "2026-01");

        pay(plot, "700.00", List.of(line(membership, "300.00")));
        awaitListeners();

        assertThat(allocations(plot, "PREPAYMENT_AUTO")).isEmpty();
        assertThat(allocationCount(plot)).isOne();
        assertThat(open(membership)).isEqualByComparingTo("300.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("400.00");
    }

    @Test
    void switchingItOnPlacesNothingUntilTheNextBooking() throws Exception {
        autoAllocation(false);
        UUID plot = plot("9", "600");
        example2(plot);
        landTaxBookedByTheMonthRun();
        UUID landTax = bookedCharge(plot, "LAND_TAX", ym(0));

        autoAllocation(true);
        awaitListeners();
        assertThat(open(landTax)).isEqualByComparingTo("120.00");

        // the next booking for the plot places the prepayment, on the oldest open charge first
        UUID march = charge(plot, "MEMBERSHIP", "10.00", "2026-03");
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> events.publishEvent(new ChargesBooked(tenant, Set.of(plot))));
        awaitListeners();
        assertThat(open(march)).isEqualByComparingTo("0.00");
        assertThat(open(landTax)).isEqualByComparingTo("0.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("20.00");
        invariantsHold(plot);
    }
}
