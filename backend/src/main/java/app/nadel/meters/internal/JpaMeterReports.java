package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterTreeLosses;
import app.nadel.meters.MeterTreeQuantity;
import app.nadel.meters.MeterTreeReport;
import app.nadel.meters.MeterTreeReportRow;
import app.nadel.meters.MeterTreeReportSlice;
import app.nadel.meters.internal.TreeQueries.TreeNode;
import app.nadel.meters.internal.TreeQueries.TreeSlice;
import app.nadel.plots.PlotCatalog.PlotFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link MeterReports} in the caller's transaction (RLS-bound). The tree is the one of the endpoint
 * ({@link TreeQueries#nodes}, the same calculation and order), flattened depth first; the derived figures (children's
 * sum, losses, plots fed) come from {@link TreeFigures}.
 */
@Component
class JpaMeterReports implements MeterReports {

    private final TreeQueries queries;

    JpaMeterReports(TreeQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MeterTreeReport tree(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
        List<MeterTreeReportRow> rows = new ArrayList<>();
        for (TreeNode root : queries.nodes(from, to)) {
            add(rows, root, 0, null);
        }
        return new MeterTreeReport(from, to, rows);
    }

    /** Adds the row of the node, then those of its subtree; returns the plots fed through the node. */
    private static Set<UUID> add(List<MeterTreeReportRow> rows, TreeNode node, int level, @Nullable UUID parent) {
        int index = rows.size();
        rows.add(null); // the node's row goes before its children's, once the plots it feeds are known
        MeterRecord meter = node.shown();
        Set<UUID> fed = new LinkedHashSet<>();
        if (meter.getKind() == MeterKind.PLOT && meter.getPlotId() != null) {
            fed.add(meter.getPlotId());
        }
        for (TreeNode child : node.children()) {
            fed.addAll(add(rows, child, level + 1, meter.getId()));
        }
        MeterTreeQuantity consumption = TreeFigures.consumption(node.total());
        MeterTreeQuantity children = null;
        MeterTreeLosses losses = null;
        if (meter.getKind() == MeterKind.SUBSTATION || meter.getKind() == MeterKind.QUARTER) {
            children = TreeFigures.childrenSum(node.children().stream().map(TreeNode::total).toList());
            losses = TreeFigures.losses(consumption, children);
        }
        PlotFacts plot = node.plot();
        rows.set(index, new MeterTreeReportRow(level, parent, meter.getId(), node.meterIds(), meter.getKind(),
                meter.getSerial(), meter.getName(), meter.getPlotId(), plot == null ? null : plot.number(),
                plot == null ? null : plot.street(), meter.getInstalledOn(), meter.getRemovedOn(), meter.isActive(),
                node.slices().stream().map(JpaMeterReports::slice).toList(), consumption, children, losses,
                List.copyOf(fed)));
        return fed;
    }

    private static MeterTreeReportSlice slice(TreeSlice slice) {
        return new MeterTreeReportSlice(slice.from(), slice.to(),
                TreeFigures.value(slice.start().meterId(), slice.start().value()),
                TreeFigures.value(slice.end().meterId(), slice.end().value()),
                TreeFigures.consumption(slice.consumption()));
    }
}
