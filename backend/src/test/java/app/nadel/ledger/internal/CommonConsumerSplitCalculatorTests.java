package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.CommonConsumerSplitCalculator.Share;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.DaySet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The split of a common consumer among plots (feature 011 §2.6; the kWh of the common consumer rows of the feature's
 * worked examples, whose amounts {@code CommonConsumerPlanningTests} checks): equally per plot, per plot-day among the
 * connected plots, equally among the service users, nothing when not charged.
 */
class CommonConsumerSplitCalculatorTests {

    private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
    private static final LocalDate OCT_30 = LocalDate.parse("2026-10-30");
    private static final LocalDate OCT_31 = LocalDate.parse("2026-10-31");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID P4 = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void streetLightingEquallyPerPlotGivesEachOfFourPlotsTwelveAndAHalfKwh() {
        // §3 "Common consumer, equal": 50 kWh, 4 active plots (one without a meter)
        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(50), CommonConsumerSplit.EQUAL_PER_PLOT,
                OCT_1, OCT_31, wholeRange(P1, P2, P3, P4));

        assertThat(kwhOf(shares)).containsExactly(Map.entry(P1, "12.500"), Map.entry(P2, "12.500"),
                Map.entry(P3, "12.500"), Map.entry(P4, "12.500"));
        assertThat(shares.get(P1).count()).isEqualTo(31);
    }

    @Test
    void theWellPumpAmongConnectedPlotsLeavesAPlotThatIsNotConnectedOut() {
        // §3 "Well pump, connected plots": 60 kWh in October, P1-P3 connected all month, P4 not
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        connected.put(P1, DaySet.of(OCT_1, OCT_31));
        connected.put(P2, DaySet.of(OCT_1, OCT_31));
        connected.put(P3, DaySet.of(OCT_1, OCT_31));

        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(60), CommonConsumerSplit.CONNECTED_PLOTS,
                OCT_1, OCT_31, connected);

        assertThat(kwhOf(shares)).containsExactly(Map.entry(P1, "20.000"), Map.entry(P2, "20.000"),
                Map.entry(P3, "20.000"));
        assertThat(shares).doesNotContainKey(P4);
    }

    @Test
    void aConnectionInsideTheRangeSharesPerPlotDay() {
        // §3 "Connection inside the range": 60 kWh over 01.10-30.10; P1, P2 all month, P3 from 16.10
        // plot-days 30 + 30 + 15 = 75 -> 0.8 kWh per plot-day
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        connected.put(P1, DaySet.of(OCT_1, OCT_30));
        connected.put(P2, DaySet.of(OCT_1, OCT_30));
        connected.put(P3, DaySet.of(LocalDate.parse("2026-10-16"), OCT_30));

        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(60), CommonConsumerSplit.CONNECTED_PLOTS,
                OCT_1, OCT_30, connected);

        assertThat(kwhOf(shares)).containsExactly(Map.entry(P1, "24.000"), Map.entry(P2, "24.000"),
                Map.entry(P3, "12.000"));
        assertThat(shares.get(P3).count()).isEqualTo(15);
        assertThat(shares.get(P3).days()).isEqualTo(DaySet.of(LocalDate.parse("2026-10-16"), OCT_30));
    }

    @Test
    void onlyTheDaysInsideTheRangeCountAndAPlotWithoutOneSharesNothing() {
        Map<UUID, DaySet> connected = new LinkedHashMap<>();
        // connected long before and after the range: only its days count
        connected.put(P1, DaySet.of(LocalDate.parse("2020-05-01"), LocalDate.parse("2030-01-01")));
        // connected after the range only
        connected.put(P2, DaySet.of(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")));
        // two short periods inside the range: 5 + 5 days
        connected.put(P3, DaySet.of(List.of(new DaySet.Range(OCT_1, LocalDate.parse("2026-10-05")),
                new DaySet.Range(LocalDate.parse("2026-10-26"), OCT_30))));

        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(40), CommonConsumerSplit.CONNECTED_PLOTS,
                OCT_1, OCT_30, connected);

        // 30 + 10 = 40 plot-days, 1 kWh each
        assertThat(kwhOf(shares)).containsExactly(Map.entry(P1, "30.000"), Map.entry(P3, "10.000"));
    }

    @Test
    void serviceUsersShareEquallyAndOtherPlotsGetNothing() {
        // §3 "Common consumer, users only": lighting 30 kWh, 3 plots marked as users
        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(30), CommonConsumerSplit.SERVICE_USERS,
                OCT_1, OCT_31, wholeRange(P1, P2, P4));

        assertThat(kwhOf(shares)).containsExactly(Map.entry(P1, "10.000"), Map.entry(P2, "10.000"),
                Map.entry(P4, "10.000"));
    }

    @Test
    void anEqualShareStaysExactAndIsNeverRoundedHere() {
        Map<UUID, Share> shares = CommonConsumerSplitCalculator.split(kwh(10), CommonConsumerSplit.EQUAL_PER_PLOT,
                OCT_1, OCT_31, wholeRange(P1, P2, P3));

        Quantity third = shares.get(P1).kwh();
        assertThat(third.plus(third).plus(third)).isEqualTo(kwh(10));
        assertThat(third.toMeterValue()).hasToString("3.333");
    }

    @Test
    void notChargedZeroConsumptionAndNobodyToShareGiveNoShares() {
        // §3 "Common consumer, not charged"
        assertThat(CommonConsumerSplitCalculator.split(kwh(30), CommonConsumerSplit.NOT_CHARGED, OCT_1, OCT_31,
                wholeRange(P1, P2))).isEmpty();
        assertThat(CommonConsumerSplitCalculator.split(Quantity.ZERO, CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31,
                wholeRange(P1, P2))).isEmpty();
        assertThat(CommonConsumerSplitCalculator.split(kwh(30), CommonConsumerSplit.SERVICE_USERS, OCT_1, OCT_31,
                Map.of())).isEmpty();
    }

    @Test
    void aNegativeConsumptionOrABackwardRangeIsRefused() {
        assertThatThrownBy(() -> CommonConsumerSplitCalculator.split(Quantity.ZERO.minus(kwh(1)),
                CommonConsumerSplit.EQUAL_PER_PLOT, OCT_1, OCT_31, wholeRange(P1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommonConsumerSplitCalculator.split(kwh(1), CommonConsumerSplit.EQUAL_PER_PLOT,
                OCT_31, OCT_1, wholeRange(P1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<UUID, DaySet> wholeRange(UUID... plots) {
        Map<UUID, DaySet> eligible = new LinkedHashMap<>();
        for (UUID plot : plots) {
            eligible.put(plot, DaySet.ALL);
        }
        return eligible;
    }

    private static Map<UUID, String> kwhOf(Map<UUID, Share> shares) {
        Map<UUID, String> kwh = new LinkedHashMap<>();
        shares.forEach((plot, share) -> kwh.put(plot, share.kwh().toMeterValue().toString()));
        return kwh;
    }

    private static Quantity kwh(int value) {
        return Quantity.of(MeterValue.of(BigDecimal.valueOf(value)));
    }
}
