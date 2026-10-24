package app.nadel.meters;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only figures of the meters for the reports (feature 013), so the reports module never reads the meter tables.
 * Every method runs in the caller's transaction ({@code Propagation.MANDATORY}: a call without one fails), which the
 * tenant-aware transaction manager has bound to the cooperative, so the meters of other cooperatives are invisible
 * (RLS). The answers carry ids, codes, meter serials and names, plot numbers and streets, and exact {@link Quantity}
 * values, never personal data.
 */
public interface MeterReports {

    /**
     * The electricity meter tree over a date range (the electricity report, feature 013 §3.4): the same calculation as
     * {@code GET /meter-tree} (one code path, so the two cannot drift), flattened in the order of the tree (each node
     * followed by its children, depth first), with the figures the endpoint leaves to the reader computed once here:
     * the sum of the children's consumption, the losses in kWh and percent of substations and quarter meters, and the
     * plots each node feeds. A node that had several parents in the range is listed under each of them with the slices
     * it spent there (money rules 6.4, tree slices). A value that is not known is never shown as zero: the figures
     * derived from it carry a {@link MeterTreeUnknownReason} instead of a number.
     *
     * @param from the first day of the range
     * @param to   the last day of the range; not before {@code from}
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    MeterTreeReport tree(LocalDate from, LocalDate to);

    /**
     * The water use of the plots over a date range (the water report, feature 013 §3.5): one {@link WaterUseRow} per
     * plot and water source it was connected to on at least one day of the range, with the connected days and the
     * cubic metres of those days (the range consumption of 010 restricted to the connected days, see
     * {@link WaterUseRow}). Plots that were not connected on any day of the range are not listed (money rules 11);
     * archived plots and common land are listed when they have such a connection (the caller decides). Rows come in
     * street order of the plots (as a meter round), a plot's sources by their first connected day. Bounded by the
     * plots of the cooperative, so no limit applies.
     *
     * @param from   the first day of the range
     * @param to     the last day of the range; not before {@code from}
     * @param source only the connections to this kind of source (every well, or the cooperative's water supply); null
     *               for every source
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    List<WaterUseRow> waterUse(LocalDate from, LocalDate to, @Nullable WaterSourceKind source);

    /**
     * The meters that have no reading in a date range (the missing readings report, feature 013 §3.6): one
     * {@link MissingReadingRow} per meter that worked through the whole range (installed before {@code from}, not
     * removed on or before {@code to}) and has no approved reading dated from {@code from} to {@code to}. A meter
     * installed or removed within the range is not listed: its initial or final value is a point of the range (money
     * rules 6.4), as the register's {@code noReadingSince} filter and {@link MeterNetwork#staleMeters} count it. A
     * water meter of a plot that was not connected to water on any day of the range is not listed (money rules 11).
     * Pending, rejected and superseded readings never count as a reading; a pending one dated in the range sets
     * {@link MissingReadingRow#pendingApprovalInRange()}.
     * <p>
     * Rows come in the order of the meter register: plot meters in street order of their plots (electricity before
     * water), then the other meters by kind (substations, quarter meters, common consumers) and name (Ukrainian
     * alphabet). At most {@code limit} rows are returned: a caller with a row cap passes the cap plus one.
     *
     * @param from      the first day of the range
     * @param to        the last day of the range; not before {@code from}
     * @param resources the resources; empty means both
     * @param kinds     the meter kinds; empty means every kind (the caller applies its own default)
     * @param plotIds   only the plot meters of these plots (a street filter resolved by the caller; the other kinds
     *                  have no plot and are left out); null for every meter. Asked 500 per query
     * @param limit     the most rows returned; at least 1
     * @throws IllegalArgumentException when {@code to} is before {@code from} or {@code limit} is below 1
     */
    List<MissingReadingRow> missingReadings(LocalDate from, LocalDate to, Set<MeterResource> resources,
            Set<MeterKind> kinds, @Nullable Collection<UUID> plotIds, int limit);
}
