package app.nadel.imports.internal;

import java.util.List;
import java.util.Map;

/**
 * What an uploaded file would do.
 *
 * @param counts   records per sheet; rows with a problem are not counted
 * @param problems every problem, in sheet and row order
 * @param rows     the normalised rows to store with a preview that can be committed; null when there are problems
 */
record ImportPlan(PlanCounts counts, List<RowProblem> problems, Map<String, Object> rows) {

    boolean hasProblems() {
        return !problems.isEmpty();
    }

    /** True when nothing is wrong and something would be created or updated. */
    boolean committable() {
        return problems.isEmpty() && counts.changesAnything();
    }
}
