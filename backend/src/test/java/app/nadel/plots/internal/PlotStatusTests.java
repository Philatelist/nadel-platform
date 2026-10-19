package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlotStatusTests {

    @Test
    void commonLandIsAlwaysGrey() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.COMMON, PlotStatusCode.RED)).isEqualTo(PlotStatusCode.GREY);
        assertThat(PlotStatusPolicy.statusOf(PlotKind.COMMON, null)).isEqualTo(PlotStatusCode.GREY);
    }

    @Test
    void plotWithoutStatusRowIsGrey() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, null)).isEqualTo(PlotStatusCode.GREY);
    }

    @Test
    void plotShowsItsCachedStatus() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, PlotStatusCode.YELLOW)).isEqualTo(PlotStatusCode.YELLOW);
    }

    @Test
    void anOwnedPlotShowsGreenButCommonLandNeverDoes() {
        assertThat(PlotStatusPolicy.statusOf(PlotKind.PLOT, PlotStatusCode.GREEN)).isEqualTo(PlotStatusCode.GREEN);
        assertThat(PlotStatusPolicy.statusOf(PlotKind.COMMON, PlotStatusCode.GREEN)).isEqualTo(PlotStatusCode.GREY);
    }

    @Test
    void initialStatusRowIsGreyWithoutDebt() {
        UUID plot = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        PlotStatusEntry entry = PlotStatusEntry.initial(plot, tenant);

        assertThat(entry.getPlotId()).isEqualTo(plot);
        assertThat(entry.getTenantId()).isEqualTo(tenant);
        assertThat(entry.getStatus()).isEqualTo(PlotStatusCode.GREY);
        assertThat(entry.getDebt()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
