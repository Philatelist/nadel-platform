package app.nadel.plots;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The plan for one ownership row. All rows of a plot get the same outcome, or none when anything is wrong with the
 * plot's rows: a plot with a problem is not counted and not imported.
 *
 * @param row      the Excel row number of the input row
 * @param outcome  null when the plot has a problem, or when it is not checked because its own row (Plots sheet) or the
 *                 member of one of its rows has a problem
 * @param plotId   the plot of the row, set together with the outcome; for a plot created by the same import it is the id
 *                 the plot will get
 * @param problems the problems of this row
 */
public record OwnershipImportItem(
        int row,
        @Nullable OwnershipImportOutcome outcome,
        @Nullable UUID plotId,
        List<OwnershipImportProblem> problems) {

    public OwnershipImportItem {
        problems = List.copyOf(problems);
    }
}
