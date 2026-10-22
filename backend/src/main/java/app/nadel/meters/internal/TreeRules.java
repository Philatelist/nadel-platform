package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.problem.ProblemCodes;
import java.util.Optional;

/**
 * Which meter may be under which in the electricity tree (spec 010, section 2.2). Pure: no Spring, no database.
 * <ul>
 * <li>A substation has no parent.</li>
 * <li>A quarter meter is under a substation.</li>
 * <li>A plot meter and a common consumer are under a substation or a quarter meter.</li>
 * <li>Water meters have no tree.</li>
 * </ul>
 * The kinds alone keep the tree at most three levels deep (substation, quarter, leaf) and free of cycles: a substation
 * is never a child and a plot meter or common consumer is never a parent, so a meter can never end up under one of its
 * own descendants.
 */
final class TreeRules {

    private TreeRules() {
    }

    /**
     * The rule a link of {@code child} under {@code parent} breaks, as the field code of {@code METER_TREE_INVALID}, or
     * empty when the kinds allow it.
     *
     * @param sameMeter whether the parent is the meter itself
     */
    static Optional<String> parentProblem(MeterKind child, MeterResource childResource, MeterKind parent,
            boolean sameMeter) {
        if (childResource != MeterResource.ELECTRICITY) {
            return Optional.of(ProblemCodes.TREE_WATER);
        }
        if (sameMeter) {
            return Optional.of(ProblemCodes.TREE_PARENT_KIND);
        }
        boolean allowed = switch (child) {
            case SUBSTATION -> false;
            case QUARTER -> parent == MeterKind.SUBSTATION;
            case PLOT, COMMON_CONSUMER -> parent == MeterKind.SUBSTATION || parent == MeterKind.QUARTER;
        };
        if (allowed) {
            return Optional.empty();
        }
        return Optional.of(child == MeterKind.QUARTER && parent == MeterKind.QUARTER
                ? ProblemCodes.TREE_QUARTER_UNDER_QUARTER : ProblemCodes.TREE_PARENT_KIND);
    }
}
