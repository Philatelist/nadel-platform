package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.ParentPeriod;
import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.Slice;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterTreeUnknownReason;
import app.nadel.meters.Quantity;
import app.nadel.meters.WaterConnections.ConnectedPeriod;
import app.nadel.meters.WaterSourceKind;
import app.nadel.meters.WaterUseRow;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The water use of the plots over a range ({@code MeterReports.waterUse}, feature 013 §3.5), in the caller's
 * transaction: the connections overlapping the range (one query), the plots' water meters and their approved readings
 * (two queries), the wells' names and the plots (bulk). The cubic metres are {@link ConsumptionCalculator}'s: the
 * connections of a plot to one source are the periods of {@link ConsumptionCalculator#splitByParent}, so each
 * connection is a tree slice (from the later of the range start and its first day to the earlier of the range end and
 * the day after its last day) over the plot's water meters, each meter clipped to the days it worked.
 */
@Component
class WaterUseQueries {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    /** The period key of the cooperative's water supply (a slice needs a parent id; it is never shown). */
    private static final UUID SUPPLY = new UUID(0L, 0L);

    /** The plot's meters, oldest first. */
    private static final Comparator<MeterRecord> BY_INSTALLATION = Comparator.comparing(MeterRecord::getInstalledOn)
            .thenComparing(MeterRecord::getId);

    private final WaterConnectionRepository connections;
    private final MeterRepository meters;
    private final MeterChains chains;
    private final PlotCatalog plots;

    WaterUseQueries(WaterConnectionRepository connections, MeterRepository meters, MeterChains chains,
            PlotCatalog plots) {
        this.connections = connections;
        this.meters = meters;
        this.chains = chains;
        this.plots = plots;
    }

    List<WaterUseRow> waterUse(LocalDate from, LocalDate to, @Nullable WaterSourceKind source) {
        // by plot, then by source in the order of the first connected day (the rows come by plot and start)
        Map<UUID, Map<@Nullable UUID, List<WaterConnectionRecord>>> byPlot = new LinkedHashMap<>();
        for (WaterConnectionRecord connection : connections.findAllOverlapping(from, to)) {
            if (source == null || (source == WaterSourceKind.METER) == (connection.getSourceMeterId() != null)) {
                byPlot.computeIfAbsent(connection.getPlotId(), plot -> new LinkedHashMap<>())
                        .computeIfAbsent(connection.getSourceMeterId(), key -> new ArrayList<>()).add(connection);
            }
        }
        if (byPlot.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<MeterRecord>> waterMeters = new HashMap<>();
        for (MeterRecord meter : meters.findByResource(MeterResource.WATER)) {
            if (meter.getKind() == MeterKind.PLOT && byPlot.containsKey(meter.getPlotId())) {
                waterMeters.computeIfAbsent(meter.getPlotId(), plot -> new ArrayList<>()).add(meter);
            }
        }
        Map<UUID, MeterHistory> histories = chains.historiesById(
                waterMeters.values().stream().flatMap(List::stream).toList());
        Map<UUID, String> wellNames = wellNames(byPlot.values().stream().flatMap(sources -> sources.keySet().stream())
                .filter(id -> id != null).collect(Collectors.toSet()));
        Map<UUID, PlotFacts> facts = plotFacts(byPlot.keySet());

        List<WaterUseRow> rows = new ArrayList<>();
        for (UUID plotId : inStreetOrder(byPlot.keySet())) {
            PlotFacts plot = facts.get(plotId);
            if (plot == null) {
                continue;
            }
            List<MeterRecord> plotMeters = waterMeters.getOrDefault(plotId, List.of()).stream()
                    .sorted(BY_INSTALLATION).toList();
            for (Map.Entry<@Nullable UUID, List<WaterConnectionRecord>> entry : byPlot.get(plotId).entrySet()) {
                UUID well = entry.getKey();
                rows.add(row(plot, well, well == null ? null : wellNames.get(well), entry.getValue(), plotMeters,
                        histories, from, to));
            }
        }
        return rows;
    }

    private static WaterUseRow row(PlotFacts plot, @Nullable UUID well, @Nullable String wellName,
            List<WaterConnectionRecord> connected, List<MeterRecord> plotMeters, Map<UUID, MeterHistory> histories,
            LocalDate from, LocalDate to) {
        List<ConnectedPeriod> periods = new ArrayList<>();
        List<ParentPeriod> parents = new ArrayList<>();
        int days = 0;
        for (WaterConnectionRecord connection : connected) {
            LocalDate first = connection.getValidFrom().isAfter(from) ? connection.getValidFrom() : from;
            LocalDate validTo = connection.getValidTo();
            LocalDate last = validTo == null || validTo.isAfter(to) ? to : validTo;
            periods.add(new ConnectedPeriod(first, last));
            days += Math.toIntExact(ChronoUnit.DAYS.between(first, last) + 1);
            parents.add(new ParentPeriod(well == null ? SUPPLY : well, connection.getValidFrom(), validTo));
        }
        WaterSourceKind kind = well == null ? WaterSourceKind.COOPERATIVE_SUPPLY : WaterSourceKind.METER;
        // the days of the slices (the same rule as below, without meters), then the meters that worked on one of them
        List<Slice> spans = ConsumptionCalculator.splitByParent(List.of(), parents, from, to);
        List<MeterRecord> measuring = plotMeters.stream().filter(meter -> workedIn(meter, spans)).toList();
        if (measuring.isEmpty()) {
            return new WaterUseRow(plot.id(), plot.number(), plot.street(), kind, well, wellName, periods, days, null,
                    null, null, false, null, null);
        }
        MeterRecord shown = measuring.getLast();
        List<MeterHistory> chain = measuring.stream().map(meter -> histories.get(meter.getId())).toList();
        Quantity total = Quantity.ZERO;
        boolean calculated = false;
        boolean unknown = false;
        LocalDate lastReadingOn = null;
        for (Slice slice : ConsumptionCalculator.splitByParent(chain, parents, from, to)) {
            switch (slice.consumption()) {
                case RangeConsumption.Known known -> {
                    total = total.plus(known.consumption());
                    calculated |= known.calculated();
                }
                case RangeConsumption.Unknown part -> {
                    unknown = true;
                    if (part.lastReadingOn() != null
                            && (lastReadingOn == null || part.lastReadingOn().isAfter(lastReadingOn))) {
                        lastReadingOn = part.lastReadingOn();
                    }
                }
            }
        }
        return unknown
                ? new WaterUseRow(plot.id(), plot.number(), plot.street(), kind, well, wellName, periods, days,
                        shown.getId(), shown.getSerial(), null, false, MeterTreeUnknownReason.VALUE_UNKNOWN,
                        lastReadingOn)
                : new WaterUseRow(plot.id(), plot.number(), plot.street(), kind, well, wellName, periods, days,
                        shown.getId(), shown.getSerial(), total, calculated, null, null);
    }

    /** Whether the meter worked on a day of one of the slices (both ends included). */
    private static boolean workedIn(MeterRecord meter, List<Slice> slices) {
        for (Slice slice : slices) {
            LocalDate removedOn = meter.getRemovedOn();
            if (!meter.getInstalledOn().isAfter(slice.to()) && (removedOn == null || !removedOn.isBefore(slice.from()))) {
                return true;
            }
        }
        return false;
    }

    private Map<UUID, String> wellNames(Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            meters.findAllById(chunk).forEach(meter -> names.put(meter.getId(), meter.getName()));
        }
        return names;
    }

    private Map<UUID, PlotFacts> plotFacts(Collection<UUID> ids) {
        Map<UUID, PlotFacts> facts = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            facts.putAll(plots.findAll(chunk));
        }
        return facts;
    }

    private List<UUID> inStreetOrder(Collection<UUID> ids) {
        // one call: the order is global, so the plots are not split into chunks here
        return plots.inStreetOrder(ids);
    }

    private static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<UUID> list = List.copyOf(ids);
        List<List<UUID>> chunks = new ArrayList<>();
        for (int start = 0; start < list.size(); start += CHUNK) {
            chunks.add(list.subList(start, Math.min(list.size(), start + CHUNK)));
        }
        return chunks;
    }
}
