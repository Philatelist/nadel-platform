package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The kinds of the electricity tree (spec 010, section 2.2): who may be under whom. */
class TreeRulesTests {

    @ParameterizedTest(name = "{0} under {1}")
    @CsvSource({
            "QUARTER, SUBSTATION",
            "PLOT, SUBSTATION",
            "PLOT, QUARTER",
            "COMMON_CONSUMER, SUBSTATION",
            "COMMON_CONSUMER, QUARTER"
    })
    void theseLinksAreAllowed(MeterKind child, MeterKind parent) {
        assertThat(TreeRules.parentProblem(child, MeterResource.ELECTRICITY, parent, false)).isEmpty();
    }

    @ParameterizedTest(name = "{0} under {1}")
    @CsvSource({
            "SUBSTATION, SUBSTATION",
            "SUBSTATION, QUARTER",
            "SUBSTATION, PLOT",
            "SUBSTATION, COMMON_CONSUMER",
            "QUARTER, PLOT",
            "QUARTER, COMMON_CONSUMER",
            "PLOT, PLOT",
            "PLOT, COMMON_CONSUMER",
            "COMMON_CONSUMER, PLOT",
            "COMMON_CONSUMER, COMMON_CONSUMER"
    })
    void theKindsRefuseTheseLinks(MeterKind child, MeterKind parent) {
        assertThat(TreeRules.parentProblem(child, MeterResource.ELECTRICITY, parent, false))
                .contains("TREE_PARENT_KIND");
    }

    @Test
    void aQuarterMeterUnderAnotherQuarterMeterHasItsOwnCode() {
        assertThat(TreeRules.parentProblem(MeterKind.QUARTER, MeterResource.ELECTRICITY, MeterKind.QUARTER, false))
                .contains("TREE_QUARTER_UNDER_QUARTER");
    }

    @Test
    void aMeterIsNeverUnderItself() {
        assertThat(TreeRules.parentProblem(MeterKind.QUARTER, MeterResource.ELECTRICITY, MeterKind.SUBSTATION, true))
                .contains("TREE_PARENT_KIND");
    }

    @Test
    void aWaterMeterHasNoTree() {
        assertThat(TreeRules.parentProblem(MeterKind.PLOT, MeterResource.WATER, MeterKind.QUARTER, false))
                .contains("TREE_WATER");
    }
}
