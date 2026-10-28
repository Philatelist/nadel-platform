package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The debts of a plot as the ledger reports them: an unmodifiable copy, a date with its days or neither, a plot without
 * payments and a prepayment that is never negative.
 */
class PlotDebtsTests {

    private static final UUID PLOT = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();

    @Test
    void theDebtsPerTypeAreAnUnmodifiableCopy() {
        Map<UUID, Money> byType = new HashMap<>(Map.of(TYPE, Money.parse("25.00")));
        PlotDebts debts = new PlotDebts(PLOT, byType, Money.parse("25.00"), LocalDate.of(2025, 3, 1), 30);
        byType.clear();

        assertThat(debts.byChargeType()).containsExactly(Map.entry(TYPE, Money.parse("25.00")));
        assertThatThrownBy(() -> debts.byChargeType().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aDateComesWithItsDaysOverdueAndNoneHasNeither() {
        assertThatThrownBy(() -> new PlotDebts(PLOT, Map.of(), Money.ZERO, LocalDate.of(2025, 3, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlotDebts(PLOT, Map.of(), Money.ZERO, null, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PlotDebts.none(PLOT)).isEqualTo(new PlotDebts(PLOT, Map.of(), Money.ZERO, null, null));
    }

    @Test
    void withoutPaymentsWhatIsChargedIsOwedAndAPrepaymentIsNeverNegative() {
        PlotDebts charged = new PlotDebts(PLOT, Map.of(TYPE, Money.parse("25.00")), Money.parse("25.00"), null, null);

        assertThat(charged.chargedByType()).containsExactly(Map.entry(TYPE, Money.parse("25.00")));
        assertThat(charged.paidByType()).containsExactly(Map.entry(TYPE, Money.ZERO));
        assertThat(charged.byChargeType()).containsExactly(Map.entry(TYPE, Money.parse("25.00")));
        assertThat(charged.charged() + " " + charged.paid() + " " + charged.total() + " " + charged.prepayment())
                .isEqualTo("25.00 0.00 25.00 0.00");
        assertThatThrownBy(() -> new PlotDebts(PLOT, Map.of(), Map.of(), Map.of(), Money.ZERO, Money.ZERO, Money.ZERO,
                Money.parse("-0.01"), null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
