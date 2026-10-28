package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.PaymentLedger.ELECTRICITY;
import static app.nadel.ledger.internal.PaymentLedger.LAND_TAX;
import static app.nadel.ledger.internal.PaymentLedger.MEMBERSHIP;
import static app.nadel.ledger.internal.PaymentLedger.PLOT;
import static app.nadel.ledger.internal.PaymentLedger.line;
import static app.nadel.ledger.internal.PaymentLedger.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Paired;
import app.nadel.ledger.internal.AllocationPlanner.Problem;
import app.nadel.ledger.internal.AllocationPlanner.Proposal;
import app.nadel.ledger.internal.AllocationPlanner.Violation;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The allocation proposal, the check of the treasurer's lines and the pairing of prepayment lines with payments
 * (feature 015 §2.2, §2.4, §3 examples 1, 2, 3, 10, 11 and 18; technical considerations 2.3). Charge type order:
 * Membership, Electricity, Land tax.
 */
class AllocationPlannerTests {

    private final PaymentLedger ledger = new PaymentLedger();

    // ---- worked examples

    @Test
    void example1_payment700_paysMembershipThenElectricity() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");

        Proposal proposal = AllocationPlanner.propose(money("700.00"), ledger.state().charges());

        assertThat(proposal.lines()).containsExactly(line(membership, "600.00"), line(electricity, "100.00"));
        assertThat(proposal.allocated()).isEqualTo(money("700.00"));
        assertThat(proposal.remainder()).isEqualTo(Money.ZERO);
        ledger.apply(ledger.pay("700.00", "2026-10-28"), proposal.lines());
        assertThat(ledger.open(electricity)).isEqualTo(money("150.00"));
        assertThat(ledger.open(membership)).isEqualTo(Money.ZERO);
        assertThat(ledger.state().prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void example2_payment1000_paysBothAndLeaves150AsPrepayment() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");

        Proposal proposal = AllocationPlanner.propose(money("1000.00"), ledger.state().charges());

        assertThat(proposal.lines()).containsExactly(line(membership, "600.00"), line(electricity, "250.00"));
        assertThat(proposal.allocated()).isEqualTo(money("850.00"));
        assertThat(proposal.remainder()).isEqualTo(money("150.00"));
        ledger.apply(ledger.pay("1000.00", "2026-10-28"), proposal.lines());
        assertThat(ledger.state().prepayment()).isEqualTo(money("150.00"));
    }

    @Test
    void example3_sameDebtDate_membershipComesFirstByTheChargeTypeOrder() {
        // electricity is booked first, so neither booking order nor the service period decides: the type order does
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "200.00", "2026-03-01");
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "300.00", "2026-03-01");

        Proposal proposal = AllocationPlanner.propose(money("400.00"), ledger.state().charges());

        assertThat(proposal.lines()).containsExactly(line(membership, "300.00"), line(electricity, "100.00"));
    }

    @Test
    void example10_treasurersLines_areSavedAsChosen_andMembershipStaysOpen200() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        List<Line> lines = List.of(line(electricity, "250.00"), line(membership, "400.00"));

        assertThat(AllocationPlanner.validate(money("700.00"), lines, ledger.state().charges())).isEmpty();

        ledger.apply(ledger.pay("700.00", "2026-10-28"), lines);
        assertThat(ledger.open(membership)).isEqualTo(money("200.00"));
        assertThat(ledger.open(electricity)).isEqualTo(Money.ZERO);
        assertThat(ledger.state().prepayment()).isEqualTo(money("50.00"));
    }

    @Test
    void example11_allocating900OfA700Payment_exceedsThePayment() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        ChargeEntry land = ledger.charge(LAND_TAX, "120.00", "2026-03-01");

        List<Violation> violations = AllocationPlanner.validate(money("700.00"),
                List.of(line(membership, "600.00"), line(electricity, "250.00"), line(land, "50.00")),
                ledger.state().charges());

        assertThat(violations).containsExactly(new Violation(null, Problem.ALLOCATION_EXCEEDS_PAYMENT));
    }

    @Test
    void example18_openingBalance1200_payment500_isAllocatedToTheOpeningChargeAnd700StaysOpen() {
        ChargeEntry opening = ledger.opening(MEMBERSHIP, "1200.00", "2026-01-01");

        Proposal proposal = AllocationPlanner.propose(money("500.00"), ledger.state().charges());

        assertThat(proposal.lines()).containsExactly(line(opening, "500.00"));
        ledger.apply(ledger.pay("500.00", "2026-10-28"), proposal.lines());
        assertThat(ledger.open(opening)).isEqualTo(money("700.00"));
    }

    // ---- the order and its tiebreaks

    @Test
    void anOlderDebtDateComesBeforeTheChargeTypeOrder() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "100.00", "2026-02-01");
        ChargeEntry land = ledger.charge(LAND_TAX, "100.00", "2026-01-01");

        assertThat(AllocationPlanner.propose(money("150.00"), ledger.state().charges()).lines())
                .containsExactly(line(land, "100.00"), line(membership, "50.00"));
    }

    @Test
    void sameDayAndType_theEarlierServicePeriodComesFirst() {
        ChargeEntry later = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01", "2026-02-01");
        ChargeEntry earlier = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01", "2026-01-01");

        assertThat(AllocationPlanner.propose(money("150.00"), ledger.state().charges()).lines())
                .containsExactly(line(earlier, "100.00"), line(later, "50.00"));
    }

    @Test
    void withoutAServicePeriodTheDebtDateStandsForItsStart() {
        // no service period counts as starting on 01.03, after the period that starts on 15.02
        ChargeEntry none = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01", null);
        ChargeEntry february = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01", "2026-02-15");

        assertThat(AllocationPlanner.propose(money("150.00"), ledger.state().charges()).lines())
                .containsExactly(line(february, "100.00"), line(none, "50.00"));
    }

    @Test
    void sameDayTypeAndPeriod_bookingOrderDecides() {
        ChargeEntry first = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01");
        ChargeEntry second = ledger.charge(MEMBERSHIP, "100.00", "2026-03-01");

        assertThat(AllocationPlanner.propose(money("150.00"), ledger.state().charges()).lines())
                .containsExactly(line(first, "100.00"), line(second, "50.00"));
    }

    @Test
    void sameBookingTime_theIdDecidesAsTextLikePostgres() {
        // as text 1… comes before 8…; UUID.compareTo would put 8… (a negative long) first
        UUID low = UUID.fromString("10000000-0000-0000-0000-000000000000");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        Instant at = Instant.parse("2026-03-01T10:00:00Z");
        LocalDate day = LocalDate.parse("2026-03-01");
        ChargeState highState = new ChargeState(high, PLOT, ChargeKind.REGULAR, MEMBERSHIP, 0, day, day, at,
                money("100.00"), Money.ZERO, false);
        ChargeState lowState = new ChargeState(low, PLOT, ChargeKind.REGULAR, MEMBERSHIP, 0, day, day, at,
                money("100.00"), Money.ZERO, false);

        assertThat(AllocationPlanner.propose(money("150.00"), List.of(highState, lowState)).lines())
                .containsExactly(new Line(low, money("100.00")), new Line(high, money("50.00")));
    }

    @Test
    void aChargeTypeTheOrderDoesNotKnowComesLast() {
        ChargeEntry custom = ledger.charge(UUID.randomUUID(), "100.00", "2026-03-01");
        ChargeEntry land = ledger.charge(LAND_TAX, "100.00", "2026-03-01");

        assertThat(AllocationPlanner.propose(money("150.00"), ledger.state().charges()).lines())
                .containsExactly(line(land, "100.00"), line(custom, "50.00"));
    }

    @Test
    void paidAndReversedChargesAreNotProposed_aPartlyPaidOneOnlyWithItsOpenAmount() {
        ChargeEntry paid = ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");
        ChargeEntry reversed = ledger.charge(MEMBERSHIP, "100.00", "2026-01-02");
        ledger.reverse(reversed);
        ChargeEntry partly = ledger.charge(ELECTRICITY, "100.00", "2026-01-03");
        ChargeEntry adjusted = ledger.charge(LAND_TAX, "100.00", "2026-01-04");
        ledger.adjust(adjusted, "-30.00");
        PaymentEntry earlier = ledger.pay("140.00", "2026-02-01");
        ledger.allocate(earlier, paid, "100.00");
        ledger.allocate(earlier, partly, "40.00");

        assertThat(AllocationPlanner.openInOrder(ledger.state().charges())).extracting(ChargeState::id)
                .containsExactly(partly.id(), adjusted.id());
        assertThat(AllocationPlanner.propose(money("500.00"), ledger.state().charges()).lines())
                .containsExactly(line(partly, "60.00"), line(adjusted, "70.00"));
    }

    @Test
    void zeroIsProposedNowhere_andANegativeAmountIsRefused() {
        ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");

        Proposal nothing = AllocationPlanner.propose(Money.ZERO, ledger.state().charges());

        assertThat(nothing.lines()).isEmpty();
        assertThat(nothing.remainder()).isEqualTo(Money.ZERO);
        assertThatThrownBy(() -> AllocationPlanner.propose(money("-1.00"), ledger.state().charges()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutOpenChargesTheWholeAmountIsTheRemainder() {
        Proposal proposal = AllocationPlanner.propose(money("50.00"), List.of());

        assertThat(proposal.lines()).isEmpty();
        assertThat(proposal.allocated()).isEqualTo(Money.ZERO);
        assertThat(proposal.remainder()).isEqualTo(money("50.00"));
    }

    // ---- the treasurer's lines

    @Test
    void aLineAboveWhatIsOpenOnItsChargeExceedsTheCharge() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");

        assertThat(AllocationPlanner.validate(money("700.00"),
                List.of(line(membership, "400.00"), line(electricity, "250.01")), ledger.state().charges()))
                .containsExactly(new Violation(1, Problem.ALLOCATION_EXCEEDS_CHARGE));
    }

    @Test
    void linesUpToThePaymentAndUpToEachOpenAmountAreFine() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");

        assertThat(AllocationPlanner.validate(money("850.00"),
                List.of(line(membership, "600.00"), line(electricity, "250.00")), ledger.state().charges())).isEmpty();
        assertThat(AllocationPlanner.validate(money("700.00"), List.of(), ledger.state().charges())).isEmpty();
    }

    @Test
    void aChargeNotOfThePlot_aReversedCharge_aRepeatedChargeAndANonPositiveLineAreRefusedPerLine() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry reversed = ledger.charge(ELECTRICITY, "250.00", "2026-02-01");
        ChargeEntry reversal = ledger.reverse(reversed);
        ChargeEntry land = ledger.charge(LAND_TAX, "120.00", "2026-03-01");

        List<Violation> violations = AllocationPlanner.validate(money("700.00"), List.of(
                line(membership, "100.00"),
                new Line(UUID.randomUUID(), money("10.00")),
                line(reversed, "10.00"),
                line(reversal, "10.00"),
                line(membership, "10.00"),
                line(land, "0.00"),
                line(land, "-5.00")), ledger.state().charges());

        assertThat(violations).containsExactly(
                new Violation(1, Problem.NOT_FOUND),
                new Violation(2, Problem.NOT_FOUND),
                new Violation(3, Problem.NOT_FOUND),
                new Violation(4, Problem.DUPLICATE),
                new Violation(5, Problem.AMOUNT_NOT_POSITIVE),
                new Violation(6, Problem.DUPLICATE));
    }

    @Test
    void theProblemOfAllLinesComesBeforeTheProblemsOfSingleLines() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");

        assertThat(AllocationPlanner.validate(money("100.00"), List.of(line(membership, "700.00")),
                ledger.state().charges())).containsExactly(new Violation(null, Problem.ALLOCATION_EXCEEDS_PAYMENT),
                        new Violation(0, Problem.ALLOCATION_EXCEEDS_CHARGE));
    }

    // ---- pairing prepayment lines with payments

    @Test
    void prepaymentLinesTakeTheMoneyOfTheOldestPaymentFirstAndContinueWithTheNext() {
        // functional spec 2.6: prepayment 150.00 from two payments, land tax 120.00 open
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");
        ChargeEntry land = ledger.charge(LAND_TAX, "120.00", "2026-03-01");
        PaymentEntry newer = ledger.pay("100.00", "2026-02-10");
        PaymentEntry older = ledger.pay("150.00", "2026-02-01");
        ledger.allocate(older, membership, "100.00");

        List<Paired> paired = AllocationPlanner.pairWithPayments(List.of(line(land, "120.00")),
                ledger.state().payments());

        assertThat(paired).containsExactly(new Paired(older.id(), land.id(), money("50.00")),
                new Paired(newer.id(), land.id(), money("70.00")));
        ledger.applyPaired(paired);
        assertThat(ledger.open(land)).isEqualTo(Money.ZERO);
        assertThat(ledger.state().prepayment()).isEqualTo(money("30.00"));
    }

    @Test
    void paymentsOfTheSameDayGiveTheirMoneyInEntryOrder_reversedAndUsedPaymentsGiveNothing() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "300.00", "2026-01-01");
        PaymentEntry used = ledger.pay("10.00", "2026-01-01");
        ledger.allocate(used, membership, "10.00");
        PaymentEntry reversed = ledger.pay("500.00", "2026-01-01");
        ledger.reversePayment(reversed);
        PaymentEntry first = ledger.pay("20.00", "2026-02-01");
        PaymentEntry second = ledger.pay("20.00", "2026-02-01");

        assertThat(AllocationPlanner.pairWithPayments(List.of(line(membership, "30.00")), ledger.state().payments()))
                .containsExactly(new Paired(first.id(), membership.id(), money("20.00")),
                        new Paired(second.id(), membership.id(), money("10.00")));
    }

    @Test
    void severalLinesShareOnePaymentInTheirOrder() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(ELECTRICITY, "100.00", "2026-01-01");
        PaymentEntry payment = ledger.pay("300.00", "2026-02-01");

        assertThat(AllocationPlanner.pairWithPayments(List.of(line(electricity, "40.00"), line(membership, "60.00")),
                ledger.state().payments())).containsExactly(
                        new Paired(payment.id(), electricity.id(), money("40.00")),
                        new Paired(payment.id(), membership.id(), money("60.00")));
    }

    @Test
    void linesAboveWhatThePaymentsHaveLeftOrNotAboveZeroCannotBePaired() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");
        ledger.pay("50.00", "2026-02-01");
        List<PaymentState> payments = ledger.state().payments();

        assertThatThrownBy(() -> AllocationPlanner.pairWithPayments(List.of(line(membership, "50.01")), payments))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationPlanner.pairWithPayments(List.of(line(membership, "0.00")), payments))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationPlanner.pairWithPayments(List.of(line(membership, "1.00")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AllocationPlanner.pairWithPayments(List.of(), payments)).isEmpty();
    }

    @Test
    void theProposalCannotBeChangedFromOutside() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "100.00", "2026-01-01");
        Proposal proposal = AllocationPlanner.propose(money("10.00"), ledger.state().charges());

        assertThatThrownBy(() -> proposal.lines().add(line(membership, "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
