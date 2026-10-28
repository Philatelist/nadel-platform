package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.PaymentLedger.ELECTRICITY;
import static app.nadel.ledger.internal.PaymentLedger.MEMBERSHIP;
import static app.nadel.ledger.internal.PaymentLedger.PLOT;
import static app.nadel.ledger.internal.PaymentLedger.money;
import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.ledger.internal.PaymentDigest.Part;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The digests of the payment previews (feature 015, technical considerations 2.6): 64 hex digits, the same whatever
 * order the charges and allocations are loaded in, different as soon as an amount, a part, an open amount or the planner
 * order changes.
 */
class PaymentDigestTests {

    private final PaymentLedger ledger = new PaymentLedger();
    private final ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
    private final ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-01-01");

    private String entry(String amount, List<ChargeState> charges) {
        return PaymentDigest.entry(money(amount), List.of(new Part(PLOT, money(amount), charges)));
    }

    @Test
    void anEntryDigestIs64HexDigitsAndStable() {
        String digest = entry("700.00", ledger.state().charges());

        assertThat(digest).matches("[0-9a-f]{64}").isEqualTo(entry("700.00", ledger.state().charges()));
    }

    @Test
    void theOrderTheChargesAreLoadedInDoesNotMatter() {
        List<ChargeState> reversed = new ArrayList<>(ledger.state().charges());
        Collections.reverse(reversed);

        assertThat(entry("700.00", reversed)).isEqualTo(entry("700.00", ledger.state().charges()));
    }

    @Test
    void anotherAmountAnotherOpenAmountOrANewChargeChangesTheEntryDigest() {
        String before = entry("700.00", ledger.state().charges());

        assertThat(entry("700.01", ledger.state().charges())).isNotEqualTo(before);
        ledger.allocate(ledger.pay("1.00", "2026-02-01"), membership, "1.00");
        String afterPayment = entry("700.00", ledger.state().charges());
        assertThat(afterPayment).isNotEqualTo(before);
        ledger.charge(ELECTRICITY, "10.00", "2026-03-01");
        assertThat(entry("700.00", ledger.state().charges())).isNotEqualTo(afterPayment);
    }

    @Test
    void thePayerOfASplitIsPartOfTheEntryDigest() {
        List<Part> parts = List.of(new Part(PLOT, money("700.00"), ledger.state().charges()));
        UUID payer = UUID.randomUUID();

        String split = PaymentDigest.entry(money("700.00"), payer, parts);

        assertThat(split).matches("[0-9a-f]{64}").isEqualTo(PaymentDigest.entry(money("700.00"), payer, parts));
        assertThat(PaymentDigest.entry(money("700.00"), UUID.randomUUID(), parts)).isNotEqualTo(split);
        assertThat(PaymentDigest.entry(money("700.00"), null, parts)).isNotEqualTo(split)
                .isEqualTo(PaymentDigest.entry(money("700.00"), parts));
    }

    @Test
    void aFullyPaidChargeIsNotInTheEntryDigest() {
        PaymentLedger other = new PaymentLedger();
        ChargeEntry paid = other.charge(MEMBERSHIP, "100.00", "2026-01-01");
        String before = PaymentDigest.entry(money("50.00"), List.of(new Part(PLOT, money("50.00"), List.of())));

        other.allocate(other.pay("100.00", "2026-02-01"), paid, "100.00");

        assertThat(PaymentDigest.entry(money("50.00"), List.of(new Part(PLOT, money("50.00"),
                other.state().charges())))).isEqualTo(before);
    }

    @Test
    void anotherPlannerOrderChangesTheEntryDigest() {
        // both charges are due the same day: swapping the charge type order swaps the planner order
        List<ChargeState> swapped = OpenAmounts.of(ledger.charges(), ledger.allocations(), ledger.payments(),
                Map.of(MEMBERSHIP, 1, ELECTRICITY, 0)).charges();

        assertThat(entry("700.00", swapped)).isNotEqualTo(entry("700.00", ledger.state().charges()));
    }

    @Test
    void theOrderAndTheAmountsOfTheSplitPartsMatter() {
        UUID plot7 = UUID.randomUUID();
        UUID plot15 = UUID.randomUUID();
        Part a = new Part(plot7, money("150.00"), List.of());
        Part b = new Part(plot15, money("50.00"), List.of());

        String digest = PaymentDigest.entry(money("200.00"), List.of(a, b));

        assertThat(PaymentDigest.entry(money("200.00"), List.of(b, a))).isNotEqualTo(digest);
        assertThat(PaymentDigest.entry(money("200.00"), List.of(new Part(plot7, money("149.99"), List.of()),
                new Part(plot15, money("50.01"), List.of())))).isNotEqualTo(digest);
    }

    @Test
    void aReallocationDigestFollowsTheLiveAllocationsOfThePaymentOnly() {
        PaymentEntry payment = ledger.pay("700.00", "2026-02-01");
        ledger.allocate(payment, membership, "600.00");
        ledger.allocate(payment, electricity, "100.00");
        PlotLedgerState state = ledger.state();
        List<AllocationState> reversed = new ArrayList<>(state.allocations());
        Collections.reverse(reversed);

        String digest = PaymentDigest.reallocation(payment.id(), state.allocations(), state.charges());

        assertThat(digest).matches("[0-9a-f]{64}")
                .isEqualTo(PaymentDigest.reallocation(payment.id(), reversed, state.charges()));
        // another payment's allocation does not count
        ledger.allocate(ledger.pay("10.00", "2026-02-02"), electricity, "10.00");
        PlotLedgerState withOther = ledger.state();
        assertThat(PaymentDigest.reallocation(payment.id(), withOther.allocations(), state.charges()))
                .isEqualTo(digest);
        // a release of one of its allocations does
        AllocationState onMembership = state.allocations().get(0);
        ledger.release(List.of(new ReleasePlanner.Release(onMembership.id(), payment.id(), membership.id(), PLOT,
                money("100.00"), ReleaseCause.CHARGE_ADJUSTED)));
        assertThat(PaymentDigest.reallocation(payment.id(), ledger.state().allocations(), state.charges()))
                .isNotEqualTo(digest);
    }

    @Test
    void aPrepaymentDigestFollowsThePrepaymentAndTheOpenCharges() {
        String digest = PaymentDigest.prepayment(PLOT, money("150.00"), ledger.state().charges());

        assertThat(digest).matches("[0-9a-f]{64}")
                .isEqualTo(PaymentDigest.prepayment(PLOT, money("150.00"), ledger.state().charges()));
        assertThat(PaymentDigest.prepayment(PLOT, money("150.01"), ledger.state().charges())).isNotEqualTo(digest);
        assertThat(PaymentDigest.prepayment(UUID.randomUUID(), money("150.00"), ledger.state().charges()))
                .isNotEqualTo(digest);
    }

    @Test
    void digestsOfDifferentKindsNeverCoincide() {
        List<ChargeState> charges = ledger.state().charges();

        assertThat(PaymentDigest.prepayment(PLOT, money("700.00"), charges)).isNotEqualTo(entry("700.00", charges));
        assertThat(PaymentDigest.reallocation(PLOT, List.of(), charges))
                .isNotEqualTo(PaymentDigest.prepayment(PLOT, money("700.00"), charges));
    }
}
