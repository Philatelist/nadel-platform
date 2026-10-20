package app.nadel.plots;

import java.util.List;

/** The plan for all plot rows of an import. */
public record PlotImportPlan(List<PlotImportItem> items) {

    public PlotImportPlan {
        items = List.copyOf(items);
    }
}
