package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.PaymentLedger.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.PaymentSplitter.Part;
import app.nadel.ledger.internal.PaymentSplitter.PlotDebt;
import app.nadel.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The proposed split of one payment over the plots of one payer (feature 015 §2.6, §3 examples 12, 13 and 14):
 * in proportion to the debts, kopecks down and the leftover to the largest remainders, ties to the plot listed first;
 * an amount of at least the total debt pays every debt and the excess goes to the first plot.
 */
class PaymentSplitterTests {

    private static final UUID PLOT_7 = UUID.randomUUID();
    private static final UUID PLOT_15 = UUID.randomUUID();
    private static final UUID PLOT_21 = UUID.randomUUID();

    private static PlotDebt debt(UUID plot, String amount) {
        return new PlotDebt(plot, money(amount));
    }

    private static Part part(UUID plot, String amount) {
        return new Part(plot, money(amount));
    }

    @Test
    void example12_payment200OverPlots7And15WithDebts300And100_is150And50() {
        assertThat(PaymentSplitter.split(money("200.00"), List.of(debt(PLOT_7, "300.00"), debt(PLOT_15, "100.00"))))
                .containsExactly(part(PLOT_7, "150.00"), part(PLOT_15, "50.00"));
    }

    @Test
    void example13_payment100OverThreeDebtsOf100_givesTheExtraKopeckToTheFirstPlot() {
        assertThat(PaymentSplitter.split(money("100.00"),
                List.of(debt(PLOT_7, "100.00"), debt(PLOT_15, "100.00"), debt(PLOT_21, "100.00"))))
                .containsExactly(part(PLOT_7, "33.34"), part(PLOT_15, "33.33"), part(PLOT_21, "33.33"));
    }

    @Test
    void example14_payment500OverDebts300And100_paysBothAndTheExcess100GoesToPlot7() {
        assertThat(PaymentSplitter.split(money("500.00"), List.of(debt(PLOT_7, "300.00"), debt(PLOT_15, "100.00"))))
                .containsExactly(part(PLOT_7, "400.00"), part(PLOT_15, "100.00"));
    }

    @Test
    void anAmountEqualToTheTotalDebtPaysEveryDebt() {
        assertThat(PaymentSplitter.split(money("400.00"), List.of(debt(PLOT_7, "300.00"), debt(PLOT_15, "100.00"))))
                .containsExactly(part(PLOT_7, "300.00"), part(PLOT_15, "100.00"));
    }

    @Test
    void whenThePlotsOweNothingTheWholeAmountGoesToTheFirstPlot() {
        assertThat(PaymentSplitter.split(money("250.00"), List.of(debt(PLOT_7, "0.00"), debt(PLOT_15, "0.00"))))
                .containsExactly(part(PLOT_7, "250.00"), part(PLOT_15, "0.00"));
    }

    @Test
    void aPlotWithoutDebtGetsNothingOfAProportionalSplit() {
        assertThat(PaymentSplitter.split(money("100.00"),
                List.of(debt(PLOT_7, "0.00"), debt(PLOT_15, "300.00"), debt(PLOT_21, "100.00"))))
                .containsExactly(part(PLOT_7, "0.00"), part(PLOT_15, "75.00"), part(PLOT_21, "25.00"));
    }

    @Test
    void thePartsAddUpToTheAmount() {
        List<Part> parts = PaymentSplitter.split(money("1000.01"),
                List.of(debt(PLOT_7, "333.33"), debt(PLOT_15, "777.77"), debt(PLOT_21, "0.01")));

        assertThat(parts.stream().map(Part::amount).reduce(Money.ZERO, Money::plus)).isEqualTo(money("1000.01"));
    }

    @Test
    void oneSplitNeedsAPlot_aNonNegativeAmount_andDebtsThatAreNotNegative() {
        assertThatThrownBy(() -> PaymentSplitter.split(money("1.00"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentSplitter.split(money("-1.00"), List.of(debt(PLOT_7, "1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> debt(PLOT_7, "-0.01")).isInstanceOf(IllegalArgumentException.class);
    }
}
