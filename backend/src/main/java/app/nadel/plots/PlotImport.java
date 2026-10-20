package app.nadel.plots;

import java.util.List;
import java.util.UUID;

/**
 * What the Excel import may ask of the plots module. The import parses the file; the plots module decides what each
 * row means for the cooperative's plots, with the same rules as when a plot is entered by hand.
 */
public interface PlotImport {

    /**
     * Plans the plot rows of an import without saving anything: for each row whether it creates a plot, updates one
     * or changes nothing, and every problem found. Plots are matched by number among the active plots, ignoring
     * case. A blank value leaves the existing value of a matched plot unchanged. Runs inside the caller's
     * transaction (the cooperative's data is read under row-level security).
     *
     * @param tenantId the cooperative of the caller
     * @param importId the id of the import, from which the ids of new plots are derived
     * @param rows     the rows in file order
     * @return one item per row, in the same order
     */
    PlotImportPlan plan(UUID tenantId, UUID importId, List<PlotImportRow> rows);
}
