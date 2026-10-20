package app.nadel.imports.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an uploaded file would do.
 *
 * @param counts    records per sheet; rows with a problem are not counted
 * @param problems  every problem, in sheet and row order
 * @param rows      the normalised rows to store with a preview that can be committed; empty when there are problems
 * @param memberIds the member of every Members row that can be imported, by its {@code external_id} in lower case: an
 *                  existing member or the id a new one will get. Recomputed from the stored rows, never stored.
 */
record ImportPlan(PlanCounts counts, List<RowProblem> problems, Map<String, Object> rows,
        Map<String, UUID> memberIds) {

    boolean hasProblems() {
        return !problems.isEmpty();
    }

    /** True when nothing is wrong and something would be created or updated. */
    boolean committable() {
        return problems.isEmpty() && counts.changesAnything();
    }
}
