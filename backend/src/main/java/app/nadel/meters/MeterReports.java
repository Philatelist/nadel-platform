package app.nadel.meters;

import java.time.LocalDate;

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
}
