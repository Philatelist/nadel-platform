package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.DebtCalculator.Line;
import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The debt of a plot (feature 011 §2.1 and §3, feature 012 §2.3, feature 015 §2.5 and §3 examples 4, 5 and 8): the sum
 * of its charges per charge type, reversals negative, adjustments with their sign, minus the net allocations to them;
 * the prepayment is the payments minus the allocations; the oldest unpaid accrual is the oldest charge that was not
 * reversed and still has an open amount above zero. Without payments the open amount is the charge plus its
 * adjustments.
 */
class DebtCalculatorTests {

    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID LAND_TAX = UUID.randomUUID();

    private static Line charge(UUID type, String amount, String accruedOn) {
        return new Line(UUID.randomUUID(), type, Money.parse(amount), LocalDate.parse(accruedOn), null, null);
    }

    private static Line reversal(Line original) {
        return new Line(UUID.randomUUID(), original.chargeTypeId(), original.amount().negate(), original.accruedOn(),
                original.id(), null);
    }

    /** An adjustment of the original: the same type and debt age, booked later in an open month. */
    private static Line adjustment(Line original, String amount) {
        return new Line(UUID.randomUUID(), original.chargeTypeId(), Money.parse(amount), original.accruedOn(), null,
                original.id());
    }

    @Test
    void noChargesNoDebt() {
        Debts debts = DebtCalculator.of(List.of());

        assertThat(debts.byType()).isEmpty();
        assertThat(debts.total()).isEqualTo(Money.ZERO);
        assertThat(debts.oldestAccrual()).isNull();
    }

    @Test
    void theDebtPerChargeTypeIsTheSumOfItsCharges() {
        // functional spec 2.1: plot 7 has membership 25.00 and land tax 17.50, total 42.50
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "25.00", "2026-10-01"),
                charge(LAND_TAX, "17.50", "2026-10-01")));

        assertThat(debts.byType()).containsExactly(Map.entry(MEMBERSHIP, Money.parse("25.00")),
                Map.entry(LAND_TAX, Money.parse("17.50")));
        assertThat(debts.total()).isEqualTo(Money.parse("42.50"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void aReversalLowersTheDebtOfItsType() {
        // feature 011 §3: charge 25.00 reversed -> REVERSAL -25.00, the debt drops by 25.00
        Line membership = charge(MEMBERSHIP, "25.00", "2026-10-01");
        Line land = charge(LAND_TAX, "17.50", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(membership, land, reversal(membership)));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.ZERO);
        assertThat(debts.byType().get(LAND_TAX)).isEqualTo(Money.parse("17.50"));
        assertThat(debts.total()).isEqualTo(Money.parse("17.50"));
    }

    @Test
    void amountsAreAddedExactly() {
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "0.10", "2026-10-01"),
                charge(MEMBERSHIP, "0.20", "2026-10-01"), charge(MEMBERSHIP, "403.06", "2026-10-04")));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.parse("403.36"));
        assertThat(debts.total().toString()).isEqualTo("403.36");
    }

    @Test
    void anOpeningBalanceAddsToTheDebtOfItsType() {
        // feature 011 §3: opening balance 1 500.00 for membership -> debt 1 500.00
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "1500.00", "2026-09-01"),
                charge(MEMBERSHIP, "25.00", "2026-10-01")));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.parse("1525.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    void theOldestAccrualSkipsReversedCharges() {
        Line old = charge(MEMBERSHIP, "1500.00", "2026-09-01");
        Line newer = charge(LAND_TAX, "17.50", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(old, newer, reversal(old)));

        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void everyChargeReversedLeavesNoOldestAccrual() {
        Line only = charge(MEMBERSHIP, "25.00", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(reversal(only), only));

        assertThat(debts.total()).isEqualTo(Money.ZERO);
        assertThat(debts.byType()).containsKey(MEMBERSHIP);
        assertThat(debts.oldestAccrual()).isNull();
    }

    @Test
    void theOldestOfSeveralOpenChargesIsFound() {
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "25.00", "2026-10-01"),
                charge(LAND_TAX, "17.50", "2026-08-01"), charge(MEMBERSHIP, "25.00", "2026-09-01")));

        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-08-01"));
    }

    // ---- adjustments (feature 012 §2.3)

    @Test
    void aNegativeAdjustmentLowersTheDebtOfItsType() {
        // functional spec 2.3: September 30.00 adjusted by -5.00, October 25.00 -> membership debt 50.00
        Line september = charge(MEMBERSHIP, "30.00", "2026-09-01");
        Line october = charge(MEMBERSHIP, "25.00", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(september, october, adjustment(september, "-5.00")));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.parse("50.00"));
        assertThat(debts.total()).isEqualTo(Money.parse("50.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    void aPositiveAdjustmentRaisesTheDebtOfItsTypeOnly() {
        // functional spec 2.1: September 25.00 expected 27.50 -> adjustment +2.50
        Line september = charge(MEMBERSHIP, "25.00", "2026-09-01");
        Line land = charge(LAND_TAX, "17.50", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(september, land, adjustment(september, "2.50")));

        assertThat(debts.byType()).containsExactly(Map.entry(MEMBERSHIP, Money.parse("27.50")),
                Map.entry(LAND_TAX, Money.parse("17.50")));
        assertThat(debts.total()).isEqualTo(Money.parse("45.00"));
    }

    @Test
    void severalAdjustmentsOfOneChargeAddUp() {
        // functional spec 2.1: 30.00 adjusted by -5.00 and later by -5.00 again -> effective 20.00
        Line september = charge(MEMBERSHIP, "30.00", "2026-09-01");

        Debts debts = DebtCalculator.of(List.of(adjustment(september, "-5.00"), september,
                adjustment(september, "-5.00")));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.parse("20.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    void aChargeAdjustedToZeroNoLongerAgesTheDebt() {
        // a charge that should no longer exist in a closed month: adjustment -effective
        Line september = charge(MEMBERSHIP, "25.00", "2026-09-01");
        Line october = charge(MEMBERSHIP, "25.00", "2026-10-01");

        Debts debts = DebtCalculator.of(List.of(september, adjustment(september, "-25.00"), october));

        assertThat(debts.byType().get(MEMBERSHIP)).isEqualTo(Money.parse("25.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void anAdjustmentNeverAgesTheDebtOnItsOwn() {
        // the adjustment is positive but its charge was cancelled by adjustments to zero first: nothing is unpaid
        Line september = charge(MEMBERSHIP, "25.00", "2026-09-01");

        Debts debts = DebtCalculator.of(List.of(september, adjustment(september, "-25.00"),
                adjustment(september, "-0.01"), adjustment(september, "0.01")));

        assertThat(debts.total()).isEqualTo(Money.ZERO);
        assertThat(debts.oldestAccrual()).isNull();
    }

    @Test
    void theDebtsCannotBeChangedFromOutside() {
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "25.00", "2026-10-01")));

        assertThatThrownBy(() -> debts.byType().put(LAND_TAX, Money.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLineNeedsItsFields() {
        assertThatThrownBy(() -> new Line(UUID.randomUUID(), MEMBERSHIP, null, LocalDate.now(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- after payments (feature 015 §2.5)

    @Test
    void withoutPaymentsTheDebtIsTheOldRuleAndThePrepaymentZero() {
        Debts debts = DebtCalculator.of(List.of(charge(MEMBERSHIP, "25.00", "2026-10-01")));

        assertThat(debts.prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void example4_example2ThenLandTax120WithAutoAllocationOn_isPaidFromThePrepaymentAnd30Remains() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry membership = ledger.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(PaymentLedger.ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("1000.00", "2026-10-28");
        ledger.allocate(payment, membership, "600.00");
        ledger.allocate(payment, electricity, "250.00");
        ChargeEntry land = ledger.charge(PaymentLedger.LAND_TAX, "120.00", "2026-10-28");

        // the automatic allocation: the current prepayment by the planner's order, paired with the payments
        AllocationPlanner.Proposal proposal = AllocationPlanner.propose(ledger.debts().prepayment(),
                ledger.state().charges());
        ledger.applyPaired(AllocationPlanner.pairWithPayments(proposal.lines(), ledger.state().payments()));

        assertThat(proposal.lines()).containsExactly(PaymentLedger.line(land, "120.00"));
        Debts debts = ledger.debts();
        assertThat(debts.byType().get(PaymentLedger.LAND_TAX)).isEqualTo(Money.ZERO);
        assertThat(debts.total()).isEqualTo(Money.ZERO);
        assertThat(debts.prepayment()).isEqualTo(Money.parse("30.00"));
        assertThat(debts.oldestAccrual()).isNull();
    }

    @Test
    void example5_example4WithAutoAllocationOff_landTaxStaysOpen120AndThePrepayment150() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry membership = ledger.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        ChargeEntry electricity = ledger.charge(PaymentLedger.ELECTRICITY, "250.00", "2026-02-01");
        PaymentEntry payment = ledger.pay("1000.00", "2026-10-28");
        ledger.allocate(payment, membership, "600.00");
        ledger.allocate(payment, electricity, "250.00");
        ChargeEntry land = ledger.charge(PaymentLedger.LAND_TAX, "120.00", "2026-10-28");

        Debts debts = ledger.debts();

        assertThat(ledger.open(land)).isEqualTo(Money.parse("120.00"));
        assertThat(debts.byType()).containsExactly(Map.entry(PaymentLedger.MEMBERSHIP, Money.ZERO),
                Map.entry(PaymentLedger.ELECTRICITY, Money.ZERO),
                Map.entry(PaymentLedger.LAND_TAX, Money.parse("120.00")));
        assertThat(debts.total()).isEqualTo(Money.parse("120.00"));
        assertThat(debts.prepayment()).isEqualTo(Money.parse("150.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-10-28"));
    }

    @Test
    void example8_aFullyPaidMembershipAdjustedBy50Up_owes50AndIsNoLongerFree() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry membership = ledger.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        ledger.allocate(ledger.pay("600.00", "2026-02-01"), membership, "600.00");
        assertThat(ledger.debts().oldestAccrual()).isNull();

        ledger.adjust(membership, "50.00");

        Debts debts = ledger.debts();
        assertThat(ledger.open(membership)).isEqualTo(Money.parse("50.00"));
        assertThat(debts.total()).isEqualTo(Money.parse("50.00"));
        // a debt above zero with a debt date: the plot status is no longer GREEN
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(debts.prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void aFullyPaidPlotWith50LeftOverOwesNothingAndHasAPrepaymentOf50() {
        // functional spec 2.9: green, also with a prepayment
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry membership = ledger.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        ledger.allocate(ledger.pay("650.00", "2026-02-01"), membership, "600.00");

        Debts debts = ledger.debts();

        assertThat(debts.total()).isEqualTo(Money.ZERO);
        assertThat(debts.byType()).containsExactly(Map.entry(PaymentLedger.MEMBERSHIP, Money.ZERO));
        assertThat(debts.oldestAccrual()).isNull();
        assertThat(debts.prepayment()).isEqualTo(Money.parse("50.00"));
    }

    @Test
    void theOldestUnpaidChargeAfterPaymentsSkipsTheFullyPaidOnes() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry january = ledger.charge(PaymentLedger.MEMBERSHIP, "100.00", "2026-01-01");
        ChargeEntry february = ledger.charge(PaymentLedger.ELECTRICITY, "100.00", "2026-02-01");
        ledger.charge(PaymentLedger.LAND_TAX, "100.00", "2026-03-01");
        PaymentEntry payment = ledger.pay("150.00", "2026-03-10");
        ledger.allocate(payment, january, "100.00");
        ledger.allocate(payment, february, "50.00");

        Debts debts = ledger.debts();

        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(debts.total()).isEqualTo(Money.parse("150.00"));
        assertThat(debts.prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void aReleaseOwesTheChargeAgainAndBecomesPrepayment() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry membership = ledger.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        AllocationEntry allocation = ledger.allocate(ledger.pay("600.00", "2026-02-01"), membership, "600.00");
        ledger.release(List.of(new ReleasePlanner.Release(allocation.id(), allocation.paymentId(),
                allocation.chargeId(), allocation.plotId(), Money.parse("200.00"), ReleaseCause.REALLOCATED)));

        Debts debts = ledger.debts();

        assertThat(debts.total()).isEqualTo(Money.parse("200.00"));
        assertThat(debts.prepayment()).isEqualTo(Money.parse("200.00"));
        assertThat(debts.oldestAccrual()).isEqualTo(LocalDate.parse("2026-01-01"));
    }

    @Test
    void aReversedPaymentLeavesNoPrepaymentAndThePrepaymentIsNeverNegative() {
        PaymentLedger reversed = new PaymentLedger();
        reversed.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        reversed.reversePayment(reversed.pay("100.00", "2026-02-01"));
        assertThat(reversed.debts().prepayment()).isEqualTo(Money.ZERO);
        assertThat(reversed.debts().total()).isEqualTo(Money.parse("600.00"));

        // rows that break the payment cap (the database refuses them) still never give a negative prepayment
        PaymentLedger broken = new PaymentLedger();
        ChargeEntry membership = broken.charge(PaymentLedger.MEMBERSHIP, "600.00", "2026-01-01");
        broken.allocate(broken.pay("100.00", "2026-02-01"), membership, "300.00");
        assertThat(broken.debts().prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void withoutAllocationsAndPaymentsBothRulesAgree() {
        PaymentLedger ledger = new PaymentLedger();
        ChargeEntry september = ledger.charge(MEMBERSHIP, "30.00", "2026-09-01");
        ledger.adjust(september, "-5.00");
        ChargeEntry october = ledger.charge(LAND_TAX, "25.00", "2026-10-01");
        ledger.reverse(october);
        ledger.opening(MEMBERSHIP, "1500.00", "2026-08-01");
        List<Line> lines = ledger.charges().stream().map(entry -> new Line(entry.id(), entry.chargeTypeId(),
                entry.amount(), entry.accruedOn(), entry.reverses(), entry.adjusts())).toList();

        Debts old = DebtCalculator.of(lines);
        Debts after = DebtCalculator.of(ledger.charges(), List.of(), List.of());

        assertThat(after).isEqualTo(old);
        assertThat(after.total()).isEqualTo(Money.parse("1525.00"));
        assertThat(after.oldestAccrual()).isEqualTo(LocalDate.parse("2026-08-01"));
    }

    @Test
    void anAllocationToAChargeThatIsNotAmongTheChargesIsRefused() {
        ChargeEntry charge = new ChargeEntry(UUID.randomUUID(), UUID.randomUUID(), ChargeKind.REGULAR, MEMBERSHIP,
                Money.parse("10.00"), LocalDate.parse("2026-10-01"), null, Instant.now(), null, null);
        AllocationEntry elsewhere = new AllocationEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                charge.plotId(), Money.parse("5.00"), null, Instant.now());

        assertThatThrownBy(() -> DebtCalculator.of(List.of(charge), List.of(elsewhere), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debtsNeedATotalAndAPrepayment() {
        assertThatThrownBy(() -> new Debts(Map.of(), Money.ZERO, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
