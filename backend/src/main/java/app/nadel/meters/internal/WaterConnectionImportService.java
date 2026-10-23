package app.nadel.meters.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.meters.MeterKind;
import app.nadel.meters.WaterConnectionImport;
import app.nadel.meters.internal.MeterAuditPublisher.WaterAction;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The meters module's side of the water column of the Excel import ({@link WaterConnectionImport}): plans the
 * connections the cells ask for with the rules of a connection by hand ({@link WaterConnectionCommands}: one lasting
 * connection, no shared day, an active common consumer meter or the cooperative's supply as the source, no common
 * land), locks what the plan relies on and connects the plots from the day of the commit. Plots are read only through
 * {@link PlotCatalog}. Every method runs in the caller's (the import's) transaction.
 */
@Service
class WaterConnectionImportService implements WaterConnectionImport {

    private static final Set<String> CONSTRAINTS = Set.of("plot_water_connection_no_overlap_ex",
            "plot_water_connection_active_uq");
    private static final String COMMON_KIND = "COMMON";
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final WaterConnectionRepository connections;
    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final MeterAuditPublisher audit;
    private final Clock clock;

    WaterConnectionImportService(WaterConnectionRepository connections, MeterRepository meters, PlotCatalog plots,
            MeterAuditPublisher audit, Clock clock) {
        this.connections = connections;
        this.meters = meters;
        this.plots = plots;
        this.audit = audit;
        this.clock = clock;
    }

    /** The Plots row of a water cell, with its plan item. */
    private record PlotLine(PlotImportRow row, PlotImportItem item) {
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Plan plan(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan, List<Row> rows) {
        if (rows.isEmpty()) {
            return new Plan(List.of());
        }
        Map<Integer, PlotLine> lines = new HashMap<>();
        for (int index = 0; index < plotRows.size(); index++) {
            lines.putIfAbsent(plotRows.get(index).row(), new PlotLine(plotRows.get(index), plotPlan.items().get(index)));
        }
        Set<UUID> storedIds = new HashSet<>();
        for (Row row : rows) {
            PlotLine line = lines.get(row.row());
            if (line != null && line.item().action() != null && line.item().action() != PlotImportAction.NEW) {
                storedIds.add(line.item().plotId());
            }
        }
        Map<UUID, PlotFacts> stored = storedIds.isEmpty() ? Map.of() : plots.findAll(storedIds);
        Map<UUID, List<WaterConnectionRecord>> byPlot = new HashMap<>();
        if (!storedIds.isEmpty()) {
            for (WaterConnectionRecord connection : connections.findByPlotIdIn(storedIds)) {
                byPlot.computeIfAbsent(connection.getPlotId(), id -> new ArrayList<>()).add(connection);
            }
        }
        Map<String, List<UUID>> sources = sourcesByName();
        LocalDate today = LocalDate.now(clock);

        List<Item> items = new ArrayList<>(rows.size());
        for (Row row : rows) {
            PlotLine line = lines.get(row.row());
            if (line == null || line.item().action() == null || line.item().plotId() == null) {
                items.add(new Item(row.row(), null, null, null, null));
                continue;
            }
            UUID plotId = line.item().plotId();
            boolean created = line.item().action() == PlotImportAction.NEW;
            if (commonLand(line.row(), created ? null : stored.get(plotId))) {
                items.add(problem(row, ProblemCodes.PLOT_COMMON_LAND));
                continue;
            }
            UUID sourceId = null;
            if (row.source() != null) {
                List<UUID> named = sources.getOrDefault(normalise(row.source()), List.of());
                if (named.size() != 1) {
                    items.add(problem(row, named.isEmpty() ? ProblemCodes.WATER_SOURCE_INVALID
                            : ProblemCodes.WATER_SOURCE_AMBIGUOUS));
                    continue;
                }
                sourceId = named.getFirst();
            }
            items.add(created ? new Item(row.row(), Action.NEW, plotId, sourceId, null)
                    : planAgainstStored(row, plotId, sourceId, byPlot.getOrDefault(plotId, List.of()), today));
        }
        return new Plan(items);
    }

    /** {@code UNCHANGED}, {@code WATER_ALREADY_CONNECTED} or {@code NEW}, from the plot's connections. */
    private static Item planAgainstStored(Row row, UUID plotId, @Nullable UUID sourceId,
            List<WaterConnectionRecord> history, LocalDate today) {
        Optional<WaterConnectionRecord> lasting = history.stream().filter(WaterConnectionRecord::lasts).findFirst();
        if (lasting.isPresent()) {
            UUID current = lasting.get().getSourceMeterId();
            if (row.source() == null || Objects.equals(current, sourceId)) {
                return new Item(row.row(), Action.UNCHANGED, plotId, current, null);
            }
            return problem(row, ProblemCodes.WATER_ALREADY_CONNECTED);
        }
        // the rule of a connection by hand: no day shared with a connection that ends today or later
        if (history.stream().anyMatch(connection -> !Objects.requireNonNull(connection.getValidTo()).isBefore(today))) {
            return problem(row, ProblemCodes.WATER_ALREADY_CONNECTED);
        }
        return new Item(row.row(), Action.NEW, plotId, sourceId, null);
    }

    /** The kind of the row decides; without one, the stored kind (a new plot without a kind is a plot). */
    private static boolean commonLand(PlotImportRow row, @Nullable PlotFacts stored) {
        String kind = row.kind() == null ? null : row.kind().strip();
        if (kind == null || kind.isEmpty()) {
            return stored != null && stored.commonLand();
        }
        return COMMON_KIND.equalsIgnoreCase(kind);
    }

    /** The active common consumer meters by name (normalised); a name may belong to more than one. */
    private Map<String, List<UUID>> sourcesByName() {
        Map<String, List<UUID>> byName = new HashMap<>();
        for (MeterRecord meter : meters.findByRemovedOnIsNull()) {
            if (meter.getKind() == MeterKind.COMMON_CONSUMER && meter.getName() != null) {
                byName.computeIfAbsent(normalise(meter.getName()), name -> new ArrayList<>()).add(meter.getId());
            }
        }
        return byName;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Plan plan) {
        Set<UUID> newPlots = new HashSet<>();
        Set<UUID> allPlots = new HashSet<>();
        for (Item item : plan.items()) {
            if (item.action() == Action.NEW) {
                newPlots.add(item.plotId());
            }
            if (item.action() != null) {
                allPlots.add(item.plotId());
            }
        }
        // plots the same import creates are not there yet, so they are simply not found
        if (!newPlots.isEmpty()) {
            plots.findAllAndLockShared(newPlots);
        }
        if (!allPlots.isEmpty()) {
            connections.findByPlotIdsForUpdate(allPlots);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuditEvent> apply(UUID tenantId, List<Row> rows, Plan plan, Actor actor) {
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        List<WaterConnectionRecord> created = new ArrayList<>();
        for (Item item : plan.items()) {
            if (item.action() == Action.NEW) {
                created.add(WaterConnectionRecord.of(tenantId, item.plotId(), item.sourceMeterId(), today, now));
            }
        }
        if (created.isEmpty()) {
            return List.of();
        }
        try {
            connections.saveAll(created);
            connections.flush();
        } catch (DataIntegrityViolationException e) {
            if (CONSTRAINTS.stream().anyMatch(constraint -> ConstraintViolations.violates(e, constraint))) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                        "The water connections changed after the preview; upload the file again.");
            }
            throw e;
        }

        Map<UUID, PlotFacts> plotFacts = plots.findAll(created.stream().map(WaterConnectionRecord::getPlotId).toList());
        Map<UUID, String> labels = new HashMap<>();
        List<AuditEvent> events = new ArrayList<>(created.size());
        for (WaterConnectionRecord connection : created) {
            PlotFacts plot = plotFacts.get(connection.getPlotId());
            String source = label(connection.getSourceMeterId(), labels);
            events.add(audit.waterEvent(tenantId, actor, WaterAction.WATER_CONNECTED, connection.getPlotId(),
                    plot == null ? null : plot.number(), List.of(
                            new FieldChange("source", null, source),
                            new FieldChange("from", null, connection.getValidFrom().toString()))));
        }
        return events;
    }

    /** The source in the audit log, as a connection by hand writes it: the meter's name or the supply's code. */
    private String label(@Nullable UUID meterId, Map<UUID, String> cache) {
        if (meterId == null) {
            return WaterConnectionCommands.SUPPLY_LABEL;
        }
        return cache.computeIfAbsent(meterId, id -> meters.findById(id).map(MeterRecord::auditLabel)
                .orElse(id.toString()));
    }

    private static Item problem(Row row, String code) {
        return new Item(row.row(), null, null, null, code);
    }

    /** Lower case, surrounding spaces dropped, runs of spaces collapsed. */
    private static String normalise(String name) {
        return SPACES.matcher(name.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
