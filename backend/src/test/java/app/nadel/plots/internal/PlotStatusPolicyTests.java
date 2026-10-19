package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The cached status after the owners of a plot changed. */
class PlotStatusPolicyTests {

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
