package app.nadel.meters;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The electricity meter tree over a date range ({@link MeterReports#tree}), one row per node and parent, in the order
 * of the tree.
 *
 * @param from the first day of the range
 * @param to   the last day of the range
 * @param rows the nodes, each followed by its children (depth first); a root has level 0
 */
public record MeterTreeReport(LocalDate from, LocalDate to, List<MeterTreeReportRow> rows) {

    public MeterTreeReport {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        rows = List.copyOf(rows);
    }
}
