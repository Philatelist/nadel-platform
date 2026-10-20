package app.nadel.plots;

import java.util.List;

/** The plan for all ownership rows of an import. */
public record OwnershipImportPlan(List<OwnershipImportItem> items) {

    public OwnershipImportPlan {
        items = List.copyOf(items);
    }
}
