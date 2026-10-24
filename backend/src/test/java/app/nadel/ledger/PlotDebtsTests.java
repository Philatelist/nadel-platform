package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The debts of a plot as the ledger reports them: an unmodifiable copy and a date with its days, or neither. */
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
}
