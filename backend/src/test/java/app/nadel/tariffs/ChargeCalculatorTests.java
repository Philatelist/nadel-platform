package app.nadel.tariffs;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The expected amount of a tariff for a plot and a month: every row of the worked examples (functional spec 2.4,
 * feature document section 3) with its exact expected text, and the edges around them.
 */
class ChargeCalculatorTests {

    private static final YearMonth NOV_2026 = YearMonth.of(2026, 11);

    private static VersionTerms recurring(String from, Basis basis, Frequency frequency, String rate) {
        return new VersionTerms(LocalDate.parse(from), basis, frequency, Rate.parse(rate), null);
    }

    private static VersionTerms fixed(String from, String rate, String due) {
        return new VersionTerms(LocalDate.parse(from), Basis.FIXED_ONE_TIME, Frequency.ONE_OFF, Rate.parse(rate),
                LocalDate.parse(due));
    }

    private static BigDecimal area(String m2) {
        return new BigDecimal(m2);
    }

    /** The amount as text; fails when the result is empty or by readings. */
    private static String amount(List<VersionTerms> versions, String m2, YearMonth month) {
        Optional<ExpectedCharge> charge = ChargeCalculator.calculate(versions, area(m2), month);
        assertThat(charge).get().isInstanceOf(ExpectedCharge.Amount.class);
        return ((ExpectedCharge.Amount) charge.get()).money().toString();
    }

    // ---- the worked examples

    @Test
    void membershipPerSotkaYearlyRateBilledMonthly() {
        // 50 x 6 / 12
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000")),
                "600.00", NOV_2026)).isEqualTo("25.00");
    }

    @Test
    void oddArea() {
        // 50 x 6.12 / 12
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000")),
                "612.00", NOV_2026)).isEqualTo("25.50");
    }

    @Test
    void roundsOnceAtTheEnd() {
        // 33.3333 x 7 / 12 = 19.444425
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "33.3333")),
                "700.00", NOV_2026)).isEqualTo("19.44");
    }

    @Test
    void perPlotQuarterly() {
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_PLOT, Frequency.QUARTERLY, "300.0000")),
                "600.00", NOV_2026)).isEqualTo("100.00");
    }

    @Test
    void versionChangeInsideTheMonthSplitsByDays() {
        // 50 until 15.11, 60 from 16.11: 12.50 + 15.00
        List<VersionTerms> versions = List.of(
                recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000"),
                recurring("2026-11-16", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "60.0000"));

        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("27.50");
    }

    @Test
    void specialPurposeFeeIsDueInItsMonthOnly() {
        List<VersionTerms> versions = List.of(fixed("2026-01-01", "1200.0000", "2026-05-01"));

        assertThat(amount(versions, "600.00", YearMonth.of(2026, 5))).isEqualTo("1200.00");
        assertThat(amount(versions, "600.00", YearMonth.of(2026, 6))).isEqualTo("0.00");
        assertThat(amount(versions, "600.00", YearMonth.of(2026, 4))).isEqualTo("0.00");
    }

    @Test
    void landTax() {
        // 35 x 6 / 12
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "35.0000")),
                "600.00", NOV_2026)).isEqualTo("17.50");
    }

    // ---- edges

    @Test
    void everyVersionContributionIsKeptExactAndRoundedOnlyOnce() {
        // 0.05 for 15 of 30 days is 0.025 twice: rounded one by one that would be 0.03 + 0.03 = 0.06
        List<VersionTerms> versions = List.of(
                recurring("2026-01-01", Basis.PER_PLOT, Frequency.MONTHLY, "0.0500"),
                recurring("2026-11-16", Basis.PER_PLOT, Frequency.MONTHLY, "0.0500"));

        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("0.05");
    }

    @Test
    void aRateWithFourDecimalsIsNeverRoundedOnItsOwn() {
        // 0.0049 per plot per month x 1 = 0.0049 -> 0.00; 0.0050 -> 0.01 (half up)
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_PLOT, Frequency.MONTHLY, "0.0049")), "600.00",
                NOV_2026)).isEqualTo("0.00");
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_PLOT, Frequency.MONTHLY, "0.0050")), "600.00",
                NOV_2026)).isEqualTo("0.01");
    }

    @Test
    void monthlyPerPlot() {
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_PLOT, Frequency.MONTHLY, "120.0000")),
                "1500.00", NOV_2026)).isEqualTo("120.00");
    }

    @Test
    void monthlyPerSotkaUsesTheAreaInSotkas() {
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.MONTHLY, "2.5000")),
                "812.40", NOV_2026)).isEqualTo("20.31"); // 2.5 x 8.124
    }

    @Test
    void yearlyPerPlotIsATwelfth() {
        assertThat(amount(List.of(recurring("2026-01-01", Basis.PER_PLOT, Frequency.YEARLY, "1000.0000")),
                "600.00", NOV_2026)).isEqualTo("83.33");
    }

    @Test
    void aFirstVersionStartingInsideTheMonthCountsForItsDaysOnly() {
        // valid 16.11 to 30.11 = 15 of 30 days
        assertThat(amount(List.of(recurring("2026-11-16", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000")), "600.00",
                NOV_2026)).isEqualTo("150.00");
    }

    @Test
    void aFirstVersionStartingOnTheFirstDayCountsTheWholeMonth() {
        assertThat(amount(List.of(recurring("2026-11-01", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000")), "600.00",
                NOV_2026)).isEqualTo("300.00");
    }

    @Test
    void aVersionStartingAfterTheMonthIsNotListed() {
        List<VersionTerms> versions = List.of(recurring("2026-12-01", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000"));

        assertThat(ChargeCalculator.calculate(versions, area("600.00"), NOV_2026)).isEmpty();
        assertThat(ChargeCalculator.shownVersion(versions, NOV_2026)).isEmpty();
    }

    @Test
    void noVersionsMeansNotListed() {
        assertThat(ChargeCalculator.calculate(List.of(), area("600.00"), NOV_2026)).isEmpty();
    }

    @Test
    void aQuarterlyTariffWithASplit() {
        // 300 until 15.11 and 600 from 16.11, per plot per quarter: 100 x 15/30 + 200 x 15/30
        List<VersionTerms> versions = List.of(
                recurring("2026-01-01", Basis.PER_PLOT, Frequency.QUARTERLY, "300.0000"),
                recurring("2026-11-16", Basis.PER_PLOT, Frequency.QUARTERLY, "600.0000"));

        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("150.00");
    }

    @Test
    void aSplitInALeapFebruaryUsesTwentyNineDays() {
        // 50 for 14 days, 60 for 15 days of 29: (50 x 6 x 14 + 60 x 6 x 15) / (29 x 12) = 27.586...
        List<VersionTerms> versions = List.of(
                recurring("2027-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000"),
                recurring("2028-02-15", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "60.0000"));

        assertThat(amount(versions, "600.00", YearMonth.of(2028, 2))).isEqualTo("27.59");
        assertThat(amount(versions, "600.00", YearMonth.of(2027, 2))).isEqualTo("25.00");
    }

    @Test
    void theOrderOfTheGivenVersionsDoesNotMatter() {
        List<VersionTerms> versions = List.of(
                recurring("2026-11-16", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "60.0000"),
                recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000"));

        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("27.50");
    }

    @Test
    void aMeterBasedVersionIsByReadings() {
        for (Basis basis : List.of(Basis.PER_KWH, Basis.PER_M3)) {
            Optional<ExpectedCharge> charge = ChargeCalculator.calculate(
                    List.of(recurring("2026-01-01", basis, Frequency.MONTHLY, "4.3200")), area("600.00"), NOV_2026);

            assertThat(charge).containsInstanceOf(ExpectedCharge.ByReadings.class);
        }
    }

    @Test
    void aMonthWhereAMeterBasedVersionOverlapsIsByReadingsEvenWhenAnotherVersionIsNot() {
        List<VersionTerms> versions = List.of(
                recurring("2026-01-01", Basis.PER_KWH, Frequency.MONTHLY, "4.3200"),
                recurring("2026-11-16", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000"));

        assertThat(ChargeCalculator.calculate(versions, area("600.00"), NOV_2026))
                .containsInstanceOf(ExpectedCharge.ByReadings.class);
        assertThat(amount(versions, "600.00", YearMonth.of(2026, 12))).isEqualTo("300.00");
    }

    @Test
    void aMeterBasedVersionThatEndedBeforeTheMonthDoesNotCount() {
        List<VersionTerms> versions = List.of(
                recurring("2026-01-01", Basis.PER_KWH, Frequency.MONTHLY, "4.3200"),
                recurring("2026-11-01", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000"));

        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("300.00");
    }

    @Test
    void aOneTimeFeeWhoseVersionWasReplacedBeforeTheDueDateContributesNothing() {
        // the fee is due 20.11 but a new version starts 10.11, so the fee version is not valid on its due date
        List<VersionTerms> versions = List.of(
                fixed("2026-01-01", "1200.0000", "2026-11-20"),
                recurring("2026-11-10", Basis.PER_PLOT, Frequency.MONTHLY, "300.0000"));

        // 300 x 21/30 = 210.00 and nothing for the fee
        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("210.00");
    }

    @Test
    void aOneTimeFeeIsNeverSplitByDays() {
        List<VersionTerms> versions = List.of(
                fixed("2026-01-01", "1200.0000", "2026-11-05"),
                fixed("2026-11-16", "1500.0000", "2026-11-20"));

        // the first version is valid on 05.11 (1200), the second on 20.11 (1500): both are due in November
        assertThat(amount(versions, "600.00", NOV_2026)).isEqualTo("2700.00");
    }

    @Test
    void aOneTimeFeeBeforeItsVersionStartsIsNotListedAndAfterItIsZero() {
        List<VersionTerms> versions = List.of(fixed("2026-05-01", "1200.0000", "2026-05-10"));

        assertThat(ChargeCalculator.calculate(versions, area("600.00"), YearMonth.of(2026, 4))).isEmpty();
        assertThat(amount(versions, "600.00", YearMonth.of(2027, 1))).isEqualTo("0.00");
    }

    @Test
    void aDueDateBeforeTheVersionStartsContributesNothing() {
        // valid from 10.06 but due 05.06: the version is not valid on its due date
        List<VersionTerms> versions = List.of(fixed("2026-06-10", "1200.0000", "2026-06-05"));

        assertThat(amount(versions, "600.00", YearMonth.of(2026, 6))).isEqualTo("0.00");
    }

    // ---- the version a listing shows

    @Test
    void theShownVersionIsTheOneValidOnTheLastDayOfTheMonth() {
        VersionTerms first = recurring("2026-01-01", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "50.0000");
        VersionTerms second = recurring("2026-11-16", Basis.PER_AREA_SOTKA, Frequency.YEARLY, "60.0000");

        assertThat(ChargeCalculator.shownVersion(List.of(second, first), NOV_2026)).containsSame(second);
        assertThat(ChargeCalculator.shownVersion(List.of(second, first), YearMonth.of(2026, 10))).containsSame(first);
    }

    @Test
    void theShownVersionOfAOneTimeFeeIsTheOneValidOnItsDueDate() {
        VersionTerms fee = fixed("2026-01-01", "1200.0000", "2026-11-05");
        VersionTerms later = fixed("2026-11-16", "1500.0000", "2027-03-01");

        assertThat(ChargeCalculator.shownVersion(List.of(fee, later), NOV_2026)).containsSame(fee);
    }
}
