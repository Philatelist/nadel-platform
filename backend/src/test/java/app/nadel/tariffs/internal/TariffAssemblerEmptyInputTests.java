package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import app.nadel.meters.MeterCatalog;
import app.nadel.plots.PlotCatalog;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The batch reads of {@link TariffAssembler} (feature 020: the plot counts of the tariff list and the plot numbers the
 * new tariff form shows): no tariffs answer an empty map without a single query, so a charge type without tariffs
 * costs no reads of the scope or the plots.
 */
class TariffAssemblerEmptyInputTests {

    private final TariffVersionRepository versions = mock(TariffVersionRepository.class);
    private final TariffScopePlotRepository scopePlots = mock(TariffScopePlotRepository.class);
    private final PlotCatalog plots = mock(PlotCatalog.class);
    private final MeterCatalog meters = mock(MeterCatalog.class);
    private final TariffAssembler assembler = new TariffAssembler(versions, scopePlots, plots, meters,
            mock(TariffConflicts.class), mock(TariffUsages.class), Clock.systemUTC());

    @Test
    void noTariffsHaveNoPlotCountsAndReadNothing() {
        assertThat(assembler.plotCounts(Map.of())).isEmpty();

        verifyNoInteractions(versions, scopePlots, plots, meters);
    }

    @Test
    void noTariffsHaveNoPlotNumbersAndReadNothing() {
        assertThat(assembler.plotNumbers(List.of())).isEmpty();

        verifyNoInteractions(versions, scopePlots, plots, meters);
    }
}
