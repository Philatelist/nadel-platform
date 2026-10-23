package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.meters.MeterKind;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.PlotWaterConnections;
import nadel.contract.model.WaterConnection;
import nadel.contract.model.WaterSource;
import nadel.contract.model.WaterSourceKind;
import nadel.contract.model.WaterSourcePlot;
import nadel.contract.model.WaterSourceRef;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the water connections of a plot (the plot card) and the water sources with their plots (the settings). A water
 * source is the cooperative's water supply (id {@value #COOPERATIVE}) or a common consumer meter (a well pump); the
 * sources listed are the supply and the active common consumer meters.
 */
@Service
class WaterConnectionQueries {

    /** The id of the cooperative's water supply in the API. */
    static final String COOPERATIVE = "cooperative";

    private final WaterConnectionRepository connections;
    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final MeterAccess access;
    private final Clock clock;

    WaterConnectionQueries(WaterConnectionRepository connections, MeterRepository meters, PlotCatalog plots,
            MeterAccess access, Clock clock) {
        this.connections = connections;
        this.meters = meters;
        this.plots = plots;
        this.access = access;
        this.clock = clock;
    }

    /**
     * The connections of a plot. A caller with {@code PLOTS_VIEW} may ask for any plot, anyone else only for a plot
     * they own now; every other case, an unknown plot and a plot of another cooperative included, is the same 404
     * {@code PLOT_NOT_FOUND}. The platform operator gets 403.
     */
    @Transactional(readOnly = true)
    PlotWaterConnections forPlot(UUID plotId, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        plots.find(plotId).orElseThrow(MeterAccess::plotNotFound);
        if (!viewer.can(Permission.PLOTS_VIEW) && !access.ownsNow(viewer, plotId)) {
            throw MeterAccess.plotNotFound();
        }
        return view(plotId);
    }

    /** The connections of a plot as the API shows them, read in the caller's transaction. */
    PlotWaterConnections view(UUID plotId) {
        List<WaterConnectionRecord> rows = connections.findByPlotIdOrderByValidFromDesc(plotId);
        Map<UUID, MeterRecord> sources = sourceMeters(rows);
        LocalDate today = LocalDate.now(clock);
        List<WaterConnection> list = new ArrayList<>();
        WaterConnection current = null;
        for (WaterConnectionRecord row : rows) {
            WaterConnection connection = new WaterConnection(row.getId(), ref(row.getSourceMeterId(), sources),
                    row.getValidFrom()).until(row.getValidTo());
            list.add(connection);
            if (row.covers(today)) {
                current = connection;
            }
        }
        return new PlotWaterConnections(plotId, current != null, list).current(current);
    }

    /** The cooperative's water supply, then the active common consumer meters by name, each with its plots. */
    @Transactional(readOnly = true)
    List<WaterSource> sources() {
        LocalDate today = LocalDate.now(clock);
        Map<Optional<UUID>, List<WaterConnectionRecord>> bySource = connections.findNotEndedBefore(today).stream()
                .collect(Collectors.groupingBy(row -> Optional.ofNullable(row.getSourceMeterId())));
        Map<UUID, PlotFacts> plotFacts = plots.findAll(bySource.values().stream().flatMap(List::stream)
                .map(WaterConnectionRecord::getPlotId).distinct().toList());
        List<WaterSource> result = new ArrayList<>();
        result.add(source(null, bySource.getOrDefault(Optional.empty(), List.of()), plotFacts));
        Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));
        meters.findByRemovedOnIsNull().stream()
                .filter(meter -> meter.getKind() == MeterKind.COMMON_CONSUMER)
                .sorted(Comparator.comparing((MeterRecord meter) -> Objects.requireNonNullElse(meter.getName(), ""),
                        collator).thenComparing(MeterRecord::getId))
                .forEach(meter -> result.add(source(meter,
                        bySource.getOrDefault(Optional.of(meter.getId()), List.of()), plotFacts)));
        return result;
    }

    /** One source as {@link #sources} shows it, read in the caller's transaction; null is the cooperative supply. */
    WaterSource source(@Nullable MeterRecord meter) {
        LocalDate today = LocalDate.now(clock);
        UUID meterId = meter == null ? null : meter.getId();
        List<WaterConnectionRecord> rows = connections.findNotEndedBefore(today).stream()
                .filter(row -> Objects.equals(row.getSourceMeterId(), meterId)).toList();
        return source(meter, rows, plots.findAll(rows.stream().map(WaterConnectionRecord::getPlotId).toList()));
    }

    private WaterSource source(@Nullable MeterRecord meter, List<WaterConnectionRecord> rows,
            Map<UUID, PlotFacts> plotFacts) {
        Map<UUID, WaterConnectionRecord> byPlot = new HashMap<>();
        rows.forEach(row -> byPlot.merge(row.getPlotId(), row,
                (a, b) -> a.getValidFrom().isBefore(b.getValidFrom()) ? a : b));
        List<WaterSourcePlot> list = new ArrayList<>();
        for (UUID plotId : plots.inNaturalOrder(byPlot.keySet())) {
            WaterConnectionRecord row = byPlot.get(plotId);
            PlotFacts plot = plotFacts.get(plotId);
            if (plot != null) {
                list.add(new WaterSourcePlot(plotId, plot.number(), row.getId(), row.getValidFrom())
                        .until(row.getValidTo()));
            }
        }
        return meter == null ? new WaterSource(COOPERATIVE, WaterSourceKind.COOPERATIVE_SUPPLY, list)
                : new WaterSource(meter.getId().toString(), WaterSourceKind.METER, list).name(meter.getName());
    }

    private Map<UUID, MeterRecord> sourceMeters(List<WaterConnectionRecord> rows) {
        List<UUID> ids = rows.stream().map(WaterConnectionRecord::getSourceMeterId).filter(Objects::nonNull)
                .distinct().toList();
        return meters.findAllById(ids).stream().collect(Collectors.toMap(MeterRecord::getId, meter -> meter));
    }

    private static WaterSourceRef ref(@Nullable UUID meterId, Map<UUID, MeterRecord> sources) {
        if (meterId == null) {
            return new WaterSourceRef(COOPERATIVE, WaterSourceKind.COOPERATIVE_SUPPLY);
        }
        MeterRecord meter = sources.get(meterId);
        return new WaterSourceRef(meterId.toString(), WaterSourceKind.METER)
                .name(meter == null ? null : meter.getName());
    }
}
