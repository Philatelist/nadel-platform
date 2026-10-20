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

    /**
     * Plans the ownership rows of an import without saving anything. The rows of one plot (same number, ignoring
     * case) are one group:
     * <ul>
     *   <li>the plot is a plot of the file (the plan of its Plots row has no problem) or an active plot of the
     *       cooperative; otherwise {@code UNKNOWN_PLOT}; common land is {@code PLOT_COMMON_LAND}; a plot whose Plots row
     *       has a problem is not checked at all, so one mistake is reported once;</li>
     *   <li>per row: {@code REQUIRED} (plot, share, date), {@code SHARE_INVALID}, {@code OWNERSHIP_DATE_INVALID} for a
     *       date after today (Europe/Kyiv);</li>
     *   <li>per group: {@code DUPLICATE_OWNER} (the same member twice), {@code OWNERSHIP_DATE_MISMATCH} (all rows of a
     *       plot carry one date), {@code SHARES_NOT_ONE} (the shares of the file add up to exactly one);</li>
     *   <li>against the recorded owners, last: no current owner gives {@code FIRST_OWNERS} (the date must be later than
     *       the end of every past owner, else {@code OWNERSHIP_DATE_INVALID}); current owners equal to the file's
     *       members and shares, dates ignored, give {@code SKIP}; any other current owners are
     *       {@code OWNERSHIP_CONFLICT}, to be solved with a change of owner.</li>
     * </ul>
     * A group is checked against the recorded owners only when none of its rows has a problem. Runs inside the
     * caller's transaction.
     *
     * @param tenantId  the cooperative of the caller
     * @param plotRows  the rows of the Plots sheet, in file order
     * @param plotPlan  their plan (from {@link #plan}); the caller replaces the item of a row it found a problem with
     *                  by an item without action
     * @param rows      the rows of the Ownership sheet in file order
     * @return one item per row, in the same order
     */
    OwnershipImportPlan planOwnership(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<OwnershipImportRow> rows);
}
