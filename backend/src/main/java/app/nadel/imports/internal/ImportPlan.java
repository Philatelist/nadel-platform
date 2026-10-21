package app.nadel.imports.internal;

import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.meters.MeterImportPlan;
import app.nadel.meters.MeterImportRow;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What an uploaded file would do.
 *
 * @param counts    records per sheet; rows with a problem are not counted
 * @param problems  every problem, in sheet and row order
 * @param rows      the normalised rows to store with a preview that can be committed; empty when there are problems
 * @param memberIds the member of every Members row that can be imported, by its {@code external_id} in lower case: an
 *                  existing member or the id a new one will get. Recomputed from the stored rows, never stored.
 * @param digest    the {@link PlanDigest} of the whole plan; null when there are problems (nothing to commit)
 * @param apply     what the plots, members and meters modules need to save the plan; null when there are problems
 */
record ImportPlan(PlanCounts counts, List<RowProblem> problems, Map<String, Object> rows,
        Map<String, UUID> memberIds, @Nullable String digest, @Nullable Apply apply) {

    /** The rows and plans the facades' {@code apply} methods take, item for item. */
    record Apply(List<PlotImportRow> plotRows, PlotImportPlan plotPlan, List<MemberImportRow> memberRows,
            MemberImportPlan memberPlan, List<OwnershipImportRow> ownershipRows, OwnershipImportPlan ownershipPlan,
            List<MeterImportRow> meterRows, MeterImportPlan meterPlan) {
    }

    boolean hasProblems() {
        return !problems.isEmpty();
    }

    /** True when nothing is wrong and something would be created or updated. */
    boolean committable() {
        return problems.isEmpty() && counts.changesAnything();
    }
}
