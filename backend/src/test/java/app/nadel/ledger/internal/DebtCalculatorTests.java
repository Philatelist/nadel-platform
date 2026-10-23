package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.DebtCalculator.Line;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The debt of a plot (feature 011 §2.1 and §3, feature 012 §2.3): the sum of its charges per charge type, reversals
 * negative, adjustments with their sign; until payments exist the oldest unpaid accrual is the oldest charge that was not
 * reversed and whose amount plus its adjustments is above zero.
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
}
