package app.nadel.plots;

import app.nadel.problem.FieldProblem;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The plan for one row. A row with problems has no action and no plot id: it cannot be imported.
 *
 * @param row           the Excel row number of the input row
 * @param action        null when there are problems
 * @param plotId        the existing plot, or the id the new plot will get; null when there are problems
 * @param changedFields names of the fields an update changes ({@code street}, {@code kind}, {@code areaM2},
 *                      {@code cadastralNumber}, {@code hasBuilding}, {@code notes}); empty otherwise
 * @param problems      one problem per invalid field; the field is named as in the plot form ({@code areaM2})
 */
public record PlotImportItem(
        int row,
        @Nullable PlotImportAction action,
        @Nullable UUID plotId,
        List<String> changedFields,
        List<FieldProblem> problems) {

    public PlotImportItem {
        changedFields = List.copyOf(changedFields);
        problems = List.copyOf(problems);
    }
}
