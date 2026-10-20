package app.nadel.plots;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
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

    /**
     * Locks the existing plots a plan touches with an exclusive row lock ({@code FOR UPDATE}), in id order, until the
     * end of the caller's transaction: the plots to be updated, the plots that get first owners and the unchanged ones
     * they share a group with. A concurrent change of owner, edit or archiving of one of them waits. This is the lock
     * a change of owner takes first (the plot, then its owners), so the caller locks plots, then members. Must run in
     * the caller's transaction.
     */
    void lock(PlotImportPlan plotPlan, OwnershipImportPlan ownershipPlan);

    /**
     * Saves what the plans say: creates the {@code NEW} plots with the ids the plan gave them (each with its GREY
     * status row), updates the {@code UPDATE} plots, then records the owners of every {@code FIRST_OWNERS} group as
     * current owners from their common date, refreshes the cached status (a plot with owners and no debt turns GREEN)
     * and builds {@code PLOT_CREATED}, {@code PLOT_UPDATED} and {@code OWNERSHIP_CHANGED} audit events credited to
     * {@code actor}, as when the same is done by hand. The events are <b>returned, not published</b>: the caller
     * publishes them (the import as {@link app.nadel.audit.AuditBatch}es) in the same transaction. {@code SKIP} groups are not touched. Rows are inserted in
     * batches. Must run in the caller's transaction, which this method never starts or commits; the members the
     * ownership rows name must exist in it (they may have been created by the same transaction).
     * <p>
     * The plans must be the ones computed in the same transaction after {@link #lock}. A plot that is gone, a number
     * or cadastral number that was taken meanwhile, or owners that now overlap are refused with 409
     * {@code IMPORT_STALE}.
     *
     * @param tenantId      the cooperative of the caller
     * @param plotRows      the rows of the Plots sheet the plan was computed from
     * @param plotPlan      their plan, item for item
     * @param ownershipRows the rows of the Ownership sheet, with the members resolved
     * @param ownershipPlan their plan, item for item
     * @param actor         who is credited in the audit log
     * @return the audit events, in the order the changes were made; the caller must publish them
     */
    List<AuditEvent> apply(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<OwnershipImportRow> ownershipRows, OwnershipImportPlan ownershipPlan, Actor actor);
}
