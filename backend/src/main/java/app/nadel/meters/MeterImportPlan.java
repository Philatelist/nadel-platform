package app.nadel.meters;

import java.util.List;

/** The plan for all meter rows of an import. */
public record MeterImportPlan(List<MeterImportItem> items) {

    public MeterImportPlan {
        items = List.copyOf(items);
    }
}
