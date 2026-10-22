package app.nadel.meters;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The plan for one meter row. A row with problems, and a row whose plot is not checked because its own row of the Plots
 * sheet has a problem, has no action: it cannot be imported.
 *
 * @param row      the Excel row number of the input row
 * @param action   null when the row has a problem or its plot is not checked
 * @param meterId  the existing active meter ({@code UNCHANGED}) or the id the new meter will get ({@code NEW}); null
 *                 without an action
 * @param plotId   the plot of the meter, an existing one or the id a plot of the same import will get; null without an
 *                 action
 * @param problems the problems of this row
 */
public record MeterImportItem(
        int row,
        @Nullable MeterImportAction action,
        @Nullable UUID meterId,
        @Nullable UUID plotId,
        List<MeterImportProblem> problems) {

    public MeterImportItem {
        problems = List.copyOf(problems);
    }
}
