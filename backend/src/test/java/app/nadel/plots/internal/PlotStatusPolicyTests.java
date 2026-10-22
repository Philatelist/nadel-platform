package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import app.nadel.plots.internal.PlotStatusPolicy.Thresholds;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The plot status (money-rules section 9, feature 011 §2.10): derived from the debt, the age of the oldest unpaid
 * charge, the red threshold and today; and the stored snapshot after the owners of a plot changed.
 */
class PlotStatusPolicyTests {

    private static final Thresholds DEFAULT = new Thresholds(Money.parse("1000.00"), 90);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static PlotStatusCode status(String debt, LocalDate oldest) {
        return PlotStatusPolicy.statusOf(PlotKind.PLOT, true, Money.parse(debt), oldest, DEFAULT, TODAY);
    }

    @Test
    void commonLandIsGreyWhateverItsDebt() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.COMMON, true, Money.parse("5000.00"), TODAY.minusYears(1),
                DEFAULT, TODAY)).isEqualTo(PlotStatusCode.GREY);
    }

    @Test
    void aPlotWithoutAnOwnerIsGreyWhateverItsDebt() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, false, Money.parse("5000.00"), TODAY.minusYears(1),
                DEFAULT, TODAY)).isEqualTo(PlotStatusCode.GREY);
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, false, Money.ZERO, null, DEFAULT, TODAY))
                .isEqualTo(PlotStatusCode.GREY);
    }

    @Test
    void anOwnedPlotWithoutADebtIsGreen() {
        assertThat(status("0.00", null)).isEqualTo(PlotStatusCode.GREEN);
        // an overpayment (later, with payments) is no debt either
        assertThat(status("-10.00", null)).isEqualTo(PlotStatusCode.GREEN);
    }

    @Test
    void anyOtherDebtIsYellow() {
        // §2.10: plot 7 gets a charge of 25,00 ₴
        assertThat(status("25.00", TODAY.withDayOfMonth(1))).isEqualTo(PlotStatusCode.YELLOW);
        // exactly the red amount is not above it
        assertThat(status("1000.00", TODAY)).isEqualTo(PlotStatusCode.YELLOW);
    }

    @Test
    void aDebtAboveTheRedAmountIsRed() {
        // §2.10: an opening balance of 1 500,00 ₴ with the red amount 1 000,00 ₴
        assertThat(status("1500.00", TODAY)).isEqualTo(PlotStatusCode.RED);
        assertThat(status("1000.01", TODAY)).isEqualTo(PlotStatusCode.RED);
    }

    @Test
    void aDebtOlderThanTheRedDaysIsRed() {
        assertThat(status("25.00", TODAY.minusDays(91))).isEqualTo(PlotStatusCode.RED);
        // exactly the red days old is not older
        assertThat(status("25.00", TODAY.minusDays(90))).isEqualTo(PlotStatusCode.YELLOW);
    }

    @Test
    void theThresholdOfTheCooperativeDecides() {
        Thresholds higher = new Thresholds(Money.parse("2000.00"), 30);

        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, true, Money.parse("1500.00"), TODAY, higher, TODAY))
                .isEqualTo(PlotStatusCode.YELLOW);
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, true, Money.parse("25.00"), TODAY.minusDays(31), higher,
                TODAY)).isEqualTo(PlotStatusCode.RED);
    }

    @Test
    void theAgeAppliesOnlyToADebt() {
        // a stale oldest date without a debt (all paid later) is green
        assertThat(status("0.00", TODAY.minusYears(2))).isEqualTo(PlotStatusCode.GREEN);
    }

    // ---- the stored snapshot after an ownership change

    @Test
    void aGreyPlotThatGetsAnOwnerTurnsGreen() {
        assertThat(PlotStatusPolicy.afterOwnershipChange(true, PlotStatusCode.GREY)).isEqualTo(PlotStatusCode.GREEN);
    }

    @Test
    void aPlotWithoutOwnersIsGreyWhateverItWas() {
        for (PlotStatusCode before : PlotStatusCode.values()) {
            assertThat(PlotStatusPolicy.afterOwnershipChange(false, before)).isEqualTo(PlotStatusCode.GREY);
        }
    }

    @Test
    void aPlotWithOwnersKeepsAStatusThatIsNotGrey() {
        for (PlotStatusCode before : List.of(PlotStatusCode.GREEN, PlotStatusCode.YELLOW, PlotStatusCode.RED)) {
            assertThat(PlotStatusPolicy.afterOwnershipChange(true, before)).isEqualTo(before);
        }
    }
}
