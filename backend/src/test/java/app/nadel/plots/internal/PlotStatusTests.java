package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlotStatusTests {

    @Test
    void initialStatusRowIsGreyWithoutDebt() {
        UUID plot = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        PlotStatusEntry entry = PlotStatusEntry.initial(plot, tenant);

        assertThat(entry.getPlotId()).isEqualTo(plot);
        assertThat(entry.getTenantId()).isEqualTo(tenant);
        assertThat(entry.getStatus()).isEqualTo(PlotStatusCode.GREY);
        assertThat(entry.getDebt()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entry.getOldestDebtDate()).isNull();
    }
}
