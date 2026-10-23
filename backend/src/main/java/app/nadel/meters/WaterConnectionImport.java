package app.nadel.meters;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the Excel import may ask of the meters module about water (feature 011 §2.8): the optional water column of the
 * Plots sheet connects plots to water from the day of the commit. The import reads the cell; a "no" or an empty cell
 * never reaches this facade, because the import never disconnects a plot (that is done on the plot card or the water
 * source).
 */
public interface WaterConnectionImport {

    /**
     * The water cell of one Plots row that asks for a connection.
     *
     * @param row    the Excel row number of the Plots row (its plot is that row's plot)
     * @param source the name of an active common consumer meter (a well) as typed, or null for the cooperative's water
     *               supply (the cell said yes)
     */
    record Row(int row, @Nullable String source) {
    }

    /** What the import does with a row. */
    enum Action {
        /** The plot is connected from the day of the commit. */
        NEW,
        /** The plot already has a lasting connection: to any source for a yes, to the named source for a name. */
        UNCHANGED
    }

    /**
     * The plan for one row.
     *
     * @param row           the Excel row number
     * @param action        null when the row has a problem or its plot is not checked (its Plots row has a problem)
     * @param plotId        the plot, an existing one or the id a plot of the same import will get; null without an
     *                      action
     * @param sourceMeterId the source of the new or the lasting connection; null for the cooperative's water supply
     *                      and without an action
     * @param problem       the code of the row's problem ({@code WATER_SOURCE_INVALID}, {@code WATER_SOURCE_AMBIGUOUS},
     *                      {@code WATER_ALREADY_CONNECTED}, {@code PLOT_COMMON_LAND}); null without one
     */
    record Item(int row, @Nullable Action action, @Nullable UUID plotId, @Nullable UUID sourceMeterId,
            @Nullable String problem) {
    }

    /** The plan for all rows, item for item. */
    record Plan(List<Item> items) {

        public Plan {
            items = List.copyOf(items);
        }
    }

    /**
     * Plans the rows without saving anything. Per row: a plot whose Plots row has a problem (its plan item has no
     * action) is not checked; common land (the kind of the row, else the stored one) is {@code PLOT_COMMON_LAND}; a
     * named source is the one active common consumer meter with that name (ignoring case and extra spaces), else
     * {@code WATER_SOURCE_INVALID}, or {@code WATER_SOURCE_AMBIGUOUS} for two or more; a plot the import creates is
     * {@code NEW}; a stored plot with a lasting connection is {@code UNCHANGED} for a yes or the name of its current
     * source and {@code WATER_ALREADY_CONNECTED} for another source; a stored plot with a connection that ends today or
     * later is {@code WATER_ALREADY_CONNECTED}; any other plot is {@code NEW}. "Today" is Europe/Kyiv. Runs inside the
     * caller's transaction.
     *
     * @param plotRows the rows of the Plots sheet, in file order
     * @param plotPlan their plan; the caller replaces the item of a row with a problem by an item without action
     * @param rows     the water cells that ask for a connection, in file order
     * @return one item per row, in the same order
     */
    Plan plan(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan, List<Row> rows);

    /**
     * Locks what a plan relies on until the end of the caller's transaction, as a connection by hand does: the stored
     * plots of the {@code NEW} rows {@code FOR SHARE}, then the connections of the stored plots of all rows with an
     * action {@code FOR UPDATE}, each in id order. The caller locks plots for its own purposes first and members after
     * this. Must run in the caller's transaction.
     */
    void lock(Plan plan);

    /**
     * Connects the plots of the {@code NEW} rows to their sources from today (Europe/Kyiv) and builds one
     * {@code WATER_CONNECTED} audit event per plot credited to {@code actor}, as when connected by hand. The events are
     * <b>returned, not published</b>: the caller publishes them in batches in the same transaction. Must run in the
     * caller's transaction, after the plots were created in it. A connection that clashes with one made meanwhile is
     * refused with 409 {@code IMPORT_STALE}.
     *
     * @param rows the rows the plan was computed from
     * @param plan the plan of {@code rows}, computed in the same transaction after {@link #lock}
     * @return the audit events in row order; the caller must publish them
     */
    List<AuditEvent> apply(UUID tenantId, List<Row> rows, Plan plan, Actor actor);
}
