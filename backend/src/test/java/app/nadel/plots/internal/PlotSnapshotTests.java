package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.audit.FieldChange;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlotSnapshotTests {

    static final PlotSnapshot BASE = new PlotSnapshot("12", "Garden St", "PLOT", "600.00", "4810136300:02:001:0001",
            false, null, false);

    @Test
    void identicalSnapshotsHaveNoDifference() {
        assertThat(PlotSnapshot.diff(BASE, BASE)).isEmpty();
    }

    @Test
    void reportsEachChangedFieldWithOldAndNewValueInAFixedOrder() {
        PlotSnapshot after = new PlotSnapshot("12a", "Orchard St", "COMMON", "650.50", "4810136300:02:001:0002",
                true, "corner", false);

        assertThat(PlotSnapshot.diff(BASE, after)).containsExactly(
                new FieldChange("number", "12", "12a"),
                new FieldChange("street", "Garden St", "Orchard St"),
                new FieldChange("kind", "PLOT", "COMMON"),
                new FieldChange("areaM2", "600.00", "650.50"),
                new FieldChange("cadastralNumber", "4810136300:02:001:0001", "4810136300:02:001:0002"),
                new FieldChange("hasBuilding", "false", "true"),
                new FieldChange("notes", null, "corner"));
    }

    @Test
    void reportsAClearedField() {
        PlotSnapshot after = new PlotSnapshot("12", null, "PLOT", "600.00", "4810136300:02:001:0001", false, null,
                false);

        assertThat(PlotSnapshot.diff(BASE, after)).containsExactly(new FieldChange("street", "Garden St", null));
    }

    @Test
    void theAreaIsComparedAsTextSoAChangeInTheDecimalsIsReported() {
        PlotSnapshot after = new PlotSnapshot("12", "Garden St", "PLOT", "600.01", "4810136300:02:001:0001", false,
                null, false);

        assertThat(PlotSnapshot.diff(BASE, after)).containsExactly(new FieldChange("areaM2", "600.00", "600.01"));
    }

    @Test
    void archivedFlagIsReportedAsFalseToTrueAndBack() {
        PlotSnapshot archived = new PlotSnapshot("12", "Garden St", "PLOT", "600.00", "4810136300:02:001:0001", false,
                null, true);

        assertThat(PlotSnapshot.diff(BASE, archived)).containsExactly(new FieldChange("archived", "false", "true"));
        assertThat(PlotSnapshot.diff(archived, BASE)).containsExactly(new FieldChange("archived", "true", "false"));
    }

    @Test
    void creationReportsEveryFilledFieldWithNullOldValue() {
        List<FieldChange> changes = PlotSnapshot.diff(PlotSnapshot.NONE, BASE);

        assertThat(changes).extracting(FieldChange::field)
                .containsExactly("number", "street", "kind", "areaM2", "cadastralNumber");
        assertThat(changes).allSatisfy(c -> assertThat(c.oldValue()).isNull());
    }

    @Test
    void creationOfAPlotWithABuildingReportsIt() {
        PlotSnapshot built = new PlotSnapshot("1", null, "PLOT", "100.00", null, true, null, false);

        assertThat(PlotSnapshot.diff(PlotSnapshot.NONE, built)).extracting(FieldChange::field)
                .containsExactly("number", "kind", "areaM2", "hasBuilding");
    }
}
