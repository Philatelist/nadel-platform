package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.PaymentLedger.ELECTRICITY;
import static app.nadel.ledger.internal.PaymentLedger.MEMBERSHIP;
import static app.nadel.ledger.internal.PaymentLedger.PLOT;
import static app.nadel.ledger.internal.PaymentLedger.line;
import static app.nadel.ledger.internal.PaymentLedger.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.AllocationPlanner.Proposal;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * When allocations are taken back (feature 015 §2.3, §3 examples 6, 7 and 9): a reversed charge releases everything
 * on it, a charge lowered below its allocations releases the excess from the latest allocations first, a reversed or
 * reallocated payment releases every allocation of it.
 */
class ReleasePlannerTests {

    private final PaymentLedger ledger = new PaymentLedger();

    private static Release release(AllocationEntry allocation, String amount, ReleaseCause cause) {
        return new Release(allocation.id(), allocation.paymentId(), allocation.chargeId(), allocation.plotId(),
                money(amount), cause);
    }

    @Test
    void example6_theJanuaryMembershipIsReversed_600IsReleased_andAutoAllocationMoves150ToElectricity() {
        // example 1: 700.00 paid membership 600.00 and electricity 100.00
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        AllocationEntry onMembership = ledger.allocate(payment, membership, "600.00");
        ledger.allocate(payment, electricity, "100.00");

        ledger.reverse(membership);
        List<Release> releases = ReleasePlanner.forCharges(ledger.state());

        assertThat(releases).containsExactly(release(onMembership, "600.00", ReleaseCause.CHARGE_REVERSED));
        ledger.release(releases);
        assertThat(ledger.state().prepayment()).isEqualTo(money("600.00"));

        // auto-allocation places the prepayment by the same order
        Proposal proposal = AllocationPlanner.propose(ledger.state().prepayment(), ledger.state().charges());
        assertThat(proposal.lines()).containsExactly(line(electricity, "150.00"));
        ledger.applyPaired(AllocationPlanner.pairWithPayments(proposal.lines(), ledger.state().payments()));
        assertThat(ledger.state().prepayment()).isEqualTo(money("450.00"));
        assertThat(ledger.open(electricity)).isEqualTo(Money.ZERO);
        assertThat(ReleasePlanner.forCharges(ledger.state())).isEmpty();
    }

    @Test
    void example7_aPaidMembershipAdjustedBy100Down_releases100OfTheLatestAllocation() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        AllocationEntry earlier = ledger.allocate(ledger.pay("400.00", "2026-02-01"), membership, "400.00");
        AllocationEntry latest = ledger.allocate(ledger.pay("200.00", "2026-03-01"), membership, "200.00");

        ledger.adjust(membership, "-100.00");
        List<Release> releases = ReleasePlanner.forCharges(ledger.state());

        assertThat(releases).containsExactly(release(latest, "100.00", ReleaseCause.CHARGE_ADJUSTED));
        assertThat(earlier.id()).isNotEqualTo(latest.id());
        ledger.release(releases);
        assertThat(ledger.state().prepayment()).isEqualTo(money("100.00"));
        assertThat(ledger.open(membership)).isEqualTo(Money.ZERO);
    }

    @Test
    void anExcessLargerThanTheLatestAllocationContinuesWithTheEarlierOne() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        AllocationEntry earlier = ledger.allocate(ledger.pay("400.00", "2026-02-01"), membership, "400.00");
        AllocationEntry latest = ledger.allocate(ledger.pay("200.00", "2026-03-01"), membership, "200.00");

        ledger.adjust(membership, "-250.00");

        assertThat(ReleasePlanner.forCharges(ledger.state())).containsExactly(
                release(latest, "200.00", ReleaseCause.CHARGE_ADJUSTED),
                release(earlier, "50.00", ReleaseCause.CHARGE_ADJUSTED));
    }

    @Test
    void anAllocationReleasedBeforeGivesOnlyWhatIsLeftOfIt() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        AllocationEntry earlier = ledger.allocate(ledger.pay("400.00", "2026-02-01"), membership, "400.00");
        AllocationEntry latest = ledger.allocate(ledger.pay("200.00", "2026-03-01"), membership, "200.00");
        ledger.adjust(membership, "-150.00");
        ledger.release(ReleasePlanner.forCharges(ledger.state()));

        ledger.adjust(membership, "-100.00");

        assertThat(ReleasePlanner.forCharges(ledger.state())).containsExactly(
                release(latest, "50.00", ReleaseCause.CHARGE_ADJUSTED),
                release(earlier, "50.00", ReleaseCause.CHARGE_ADJUSTED));
    }

    @Test
    void example9_thePayment700IsReversed_releases600And100_andTheDebtsAreBackTo600And250() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        AllocationEntry onMembership = ledger.allocate(payment, membership, "600.00");
        AllocationEntry onElectricity = ledger.allocate(payment, electricity, "100.00");

        List<Release> releases = ReleasePlanner.forPayment(payment.id(), ledger.state().allocations(),
                ReleaseCause.PAYMENT_REVERSED);

        assertThat(releases).containsExactly(release(onElectricity, "100.00", ReleaseCause.PAYMENT_REVERSED),
                release(onMembership, "600.00", ReleaseCause.PAYMENT_REVERSED));
        ledger.reversePayment(payment);
        ledger.release(releases);
        Debts debts = ledger.debts();
        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(money("600.00"));
        assertThat(debts.byType().get(ELECTRICITY)).isEqualTo(money("250.00"));
        assertThat(debts.total()).isEqualTo(money("850.00"));
        assertThat(debts.prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void aReallocationReleasesWhatIsLeftOfEveryAllocationOfThePaymentOnly() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        AllocationEntry onMembership = ledger.allocate(payment, membership, "600.00");
        AllocationEntry onElectricity = ledger.allocate(payment, electricity, "100.00");
        ledger.allocate(ledger.pay("50.00", "2026-10-28"), electricity, "50.00");
        ledger.release(List.of(release(onMembership, "200.00", ReleaseCause.CHARGE_ADJUSTED)));

        assertThat(ReleasePlanner.forPayment(payment.id(), ledger.state().allocations(), ReleaseCause.REALLOCATED))
                .containsExactly(release(onElectricity, "100.00", ReleaseCause.REALLOCATED),
                        release(onMembership, "400.00", ReleaseCause.REALLOCATED));
    }

    @Test
    void theStateAfterThePlannedReleasesEqualsTheStateAfterWritingThem() {
        // the FS 2.6 reallocation: the preview reads the open amounts as if the payment's allocations were released
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        ledger.allocate(payment, membership, "600.00");
        ledger.allocate(payment, electricity, "100.00");
        ledger.allocate(ledger.pay("50.00", "2026-10-28"), electricity, "50.00");
        PlotLedgerState before = ledger.state();
        List<Release> releases = ReleasePlanner.forPayment(payment.id(), before.allocations(),
                ReleaseCause.REALLOCATED);

        PlotLedgerState asIf = before.afterReleases(releases);

        ledger.release(releases);
        assertThat(asIf).isEqualTo(ledger.state());
        assertThat(asIf.charge(membership.id()).orElseThrow().open()).isEqualTo(money("600.00"));
        assertThat(asIf.charge(electricity.id()).orElseThrow().open()).isEqualTo(money("200.00"));
        assertThat(asIf.payment(payment.id()).orElseThrow().left()).isEqualTo(money("700.00"));
        assertThat(asIf.prepayment()).isEqualTo(money("700.00"));
        assertThat(before.afterReleases(List.of())).isSameAs(before);
    }

    @Test
    void aFullyReleasedAllocationIsNotReleasedAgain() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        PaymentEntry payment = ledger.pay("600.00", "2026-10-28");
        AllocationEntry allocation = ledger.allocate(payment, membership, "600.00");
        ledger.release(List.of(release(allocation, "600.00", ReleaseCause.REALLOCATED)));

        assertThat(ReleasePlanner.forPayment(payment.id(), ledger.state().allocations(),
                ReleaseCause.PAYMENT_REVERSED)).isEmpty();
    }

    @Test
    void chargesThatHoldAtMostTheirEffectiveAmountReleaseNothing() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ledger.allocate(ledger.pay("600.00", "2026-02-01"), membership, "600.00");
        ledger.adjust(membership, "50.00");
        ChargeEntry unpaid = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        ledger.reverse(unpaid);

        assertThat(ReleasePlanner.forCharges(ledger.state())).isEmpty();
    }

    @Test
    void allocationsOfTheSameMomentAreReleasedByIdDescendingAsText() {
        Instant at = Instant.parse("2026-03-01T10:00:00Z");
        UUID charge = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        AllocationState low = new AllocationState(UUID.fromString("10000000-0000-0000-0000-000000000000"), payment,
                charge, PLOT, money("10.00"), at);
        AllocationState high = new AllocationState(UUID.fromString("80000000-0000-0000-0000-000000000000"), payment,
                charge, PLOT, money("10.00"), at);

        assertThat(ReleasePlanner.forPayment(payment, List.of(low, high), ReleaseCause.PAYMENT_REVERSED))
                .extracting(Release::allocationId).containsExactly(high.id(), low.id());
    }

    @Test
    void aPaymentIsReleasedOnlyBecauseItIsReversedOrReallocated_andAReleaseIsAboveZero() {
        assertThatThrownBy(() -> ReleasePlanner.forPayment(UUID.randomUUID(), List.of(),
                ReleaseCause.CHARGE_REVERSED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Release(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PLOT,
                Money.ZERO, ReleaseCause.REALLOCATED)).isInstanceOf(IllegalArgumentException.class);
    }
}
