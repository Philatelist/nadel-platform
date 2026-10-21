package app.nadel.meters;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.util.List;
import java.util.UUID;

/**
 * What the Excel import may ask of the meters module: the rows of the Meters sheet. The import parses the file; the
 * meters module decides what each row means for the cooperative's meters. The import adds plot meters only (the
 * electricity tree is set up on its screen) and never replaces one: replacement is done in the register.
 */
public interface MeterImport {

    /**
     * Plans the meter rows of an import without saving anything. Per row, in this order:
     * <ul>
     *   <li>the cells: {@code REQUIRED} (every field), {@code INVALID} ({@code resource} is not {@code ELECTRICITY} or
     *       {@code WATER}, any letter case), {@code TOO_LONG} ({@code serial} over 50 characters),
     *       {@code METER_VALUE_INVALID} ({@code initialReading} negative, with more than three decimals or eleven
     *       integer digits), {@code READING_DATE_IN_FUTURE} ({@code installedOn} after today, Europe/Kyiv);</li>
     *   <li>{@code DUPLICATE_IN_FILE} on {@code resource}: an earlier row without problems names the same plot (ignoring
     *       case) and resource;</li>
     *   <li>the plot: a plot of the file (the plan of its Plots row; its kind from the row, else the stored one) or an
     *       active plot of the cooperative, else {@code UNKNOWN_PLOT}; common land is {@code PLOT_COMMON_LAND} (both on
     *       {@code plotNumber}); a plot whose Plots row has a problem is not checked at all, so one mistake is reported
     *       once;</li>
     *   <li>against the stored meters, last: no active meter of the plot and resource gives {@code NEW}; an active meter
     *       with the row's serial number (ignoring case and surrounding spaces) gives {@code UNCHANGED}, its dates and
     *       readings not compared; any other active meter is {@code METER_ACTIVE_EXISTS} on {@code serial} with the
     *       parameter {@code plot} (the stored plot number), to be replaced in the register.</li>
     * </ul>
     * Runs inside the caller's transaction (the cooperative's data is read under row-level security).
     *
     * @param tenantId the cooperative of the caller
     * @param importId the id of the import, from which the ids of new meters are derived
     * @param plotRows the rows of the Plots sheet, in file order
     * @param plotPlan their plan; the caller replaces the item of a row it found a problem with by an item without
     *                 action
     * @param rows     the rows of the Meters sheet in file order
     * @return one item per row, in the same order
     */
    MeterImportPlan plan(UUID tenantId, UUID importId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<MeterImportRow> rows);

    /**
     * Locks what a plan relies on until the end of the caller's transaction: the existing plots that get a new meter
     * {@code FOR SHARE} (the lock a registration by hand takes, so a delete, archive or edit of such a plot waits),
     * then the active meters of the {@code UNCHANGED} rows {@code FOR UPDATE} (so a removal or replacement waits), each
     * set in id order. The caller locks plots for its own purposes first, then calls this, then locks members. Must run
     * in the caller's transaction.
     */
    void lock(MeterImportPlan plan);

    /**
     * Registers the {@code NEW} meters with the ids the plan gave them, as active plot meters with their installation
     * date and initial reading, and builds one {@code METER_REGISTERED} audit event per meter credited to
     * {@code actor}, as when a meter is registered by hand. The events are <b>returned, not published</b>: the caller
     * publishes them (as {@link app.nadel.audit.AuditBatch}es) in the same transaction. {@code UNCHANGED} rows are not
     * touched. Rows are inserted in batches. Must run in the caller's transaction, which this method never starts or
     * commits; the plots the rows name must exist in it (they may have been created by the same transaction).
     * <p>
     * The plan must be the one computed in the same transaction after {@link #lock}. An active meter of a plot and
     * resource registered meanwhile is refused with 409 {@code IMPORT_STALE}.
     *
     * @param tenantId the cooperative of the caller
     * @param rows     the rows the plan was computed from
     * @param plan     the plan of {@code rows}, item for item
     * @param actor    who is credited in the audit log
     * @return the audit events of the new meters, in row order; the caller must publish them
     */
    List<AuditEvent> apply(UUID tenantId, List<MeterImportRow> rows, MeterImportPlan plan, Actor actor);
}
