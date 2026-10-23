package app.nadel.meters.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.meters.MeterKind;
import app.nadel.meters.internal.MeterAuditPublisher.WaterAction;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.PlotWaterConnections;
import nadel.contract.model.WaterSource;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connects plots to water and disconnects them (feature 011 §2.8), one plot from its card or many at once from a water
 * source. Every method is one transaction that also publishes one audit entry per plot ({@code WATER_CONNECTED},
 * {@code WATER_DISCONNECTED}, entity type {@code PLOT}). The caller holds {@code METERS_MANAGE} (checked on the
 * controller); the cooperative comes from the caller's {@link AccessProfile}.
 * <p>
 * Rules: a connection starts on {@code from} and lasts until it is ended on {@code until} (both are connected days); a
 * plot is connected once on any day and has at most one lasting connection, so a new connection is refused with
 * {@code WATER_ALREADY_CONNECTED} while the plot has a lasting one or one that reaches {@code from}. The source is the
 * cooperative's water supply or an active common consumer meter ({@code WATER_SOURCE_INVALID} otherwise). Archived
 * plots and common land are not connected. The history is kept: a disconnection only ends the row.
 * <p>
 * Locks: the plots {@code FOR SHARE} first ({@link PlotCatalog#findAndLockShared}; a delete or archive of a plot holds
 * it {@code FOR UPDATE}), then their connections {@code FOR UPDATE} in id order. Two connections of a free plot at once
 * find nothing to lock; the exclusion constraint and the partial unique index refuse the second one, mapped to
 * {@code WATER_ALREADY_CONNECTED} at the flush.
 */
@Service
class WaterConnectionCommands {

    private static final Set<String> CONSTRAINTS = Set.of("plot_water_connection_no_overlap_ex",
            "plot_water_connection_active_uq");

    /** The audit value of the cooperative's water supply (clients translate {@code base:} codes). */
    static final String SUPPLY_LABEL = "base:COOPERATIVE_SUPPLY";

    private final WaterConnectionRepository connections;
    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final WaterConnectionQueries queries;
    private final MeterAuditPublisher audit;
    private final Clock clock;

    WaterConnectionCommands(WaterConnectionRepository connections, MeterRepository meters, PlotCatalog plots,
            WaterConnectionQueries queries, MeterAuditPublisher audit, Clock clock) {
        this.connections = connections;
        this.meters = meters;
        this.plots = plots;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
    }

    /** Connects one plot to the meter {@code sourceMeterId}, or to the cooperative's water supply when it is null. */
    @Transactional
    PlotWaterConnections connect(UUID plotId, @Nullable UUID sourceMeterId, LocalDate from, AccessProfile actor) {
        PlotFacts plot = plots.findAndLockShared(plotId).orElseThrow(MeterAccess::plotNotFound);
        if (plot.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED,
                    "An archived plot is not connected to water.");
        }
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "Common land is not connected to water.");
        }
        MeterRecord source = null;
        if (sourceMeterId != null) {
            source = meters.findById(sourceMeterId).filter(WaterConnectionCommands::isActiveSource)
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.WATER_SOURCE_INVALID,
                            "The water source is not an active common consumer meter.",
                            List.of(new FieldProblem("sourceMeterId", ProblemCodes.WATER_SOURCE_INVALID))));
        }
        if (clashes(connections.findByPlotIdsForUpdate(List.of(plotId)), from)) {
            throw alreadyConnected(List.of(new FieldProblem("from", ProblemCodes.WATER_ALREADY_CONNECTED)));
        }
        Instant now = clock.instant();
        WaterConnectionRecord row = WaterConnectionRecord.of(Objects.requireNonNull(actor.tenantId()), plotId,
                sourceMeterId, from, now);
        insert(List.of(row), List.of(new FieldProblem("from", ProblemCodes.WATER_ALREADY_CONNECTED)));
        publishConnected(actor, plot, row, label(source));
        return queries.view(plotId);
    }

    /** Ends one connection of the plot on {@code until}, its last connected day. */
    @Transactional
    PlotWaterConnections end(UUID plotId, UUID connectionId, LocalDate until, AccessProfile actor) {
        PlotFacts plot = plots.findAndLockShared(plotId).orElseThrow(MeterAccess::plotNotFound);
        WaterConnectionRecord row = connections.findByPlotIdsForUpdate(List.of(plotId)).stream()
                .filter(c -> c.getId().equals(connectionId)).findFirst()
                .orElseThrow(ApiProblemException::notFound);
        if (!row.lasts()) {
            throw notConnected(List.of());
        }
        if (until.isBefore(row.getValidFrom())) {
            throw invalid(List.of(new FieldProblem("until", ProblemCodes.INVALID)));
        }
        row.endOn(until, clock.instant());
        connections.flush();
        publishDisconnected(actor, plot, row, label(sourceOf(row)));
        return queries.view(plotId);
    }

    /** Connects every plot to the source (all or nothing). */
    @Transactional
    WaterSource connectAll(String sourceId, List<UUID> plotIds, LocalDate from, AccessProfile actor) {
        MeterRecord source = resolve(sourceId, true);
        Map<UUID, PlotFacts> found = lockPlots(plotIds);
        List<FieldProblem> problems = plotProblems(plotIds, found, true);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        Map<UUID, List<WaterConnectionRecord>> byPlot = connectionsOf(plotIds);
        List<FieldProblem> clashes = new ArrayList<>();
        for (int i = 0; i < plotIds.size(); i++) {
            if (clashes(byPlot.getOrDefault(plotIds.get(i), List.of()), from)) {
                clashes.add(new FieldProblem(field(i), ProblemCodes.WATER_ALREADY_CONNECTED));
            }
        }
        if (!clashes.isEmpty()) {
            throw alreadyConnected(clashes);
        }
        Instant now = clock.instant();
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        UUID meterId = source == null ? null : source.getId();
        List<WaterConnectionRecord> rows = plotIds.stream()
                .map(plotId -> WaterConnectionRecord.of(tenantId, plotId, meterId, from, now)).toList();
        insert(rows, List.of(new FieldProblem("plotIds", ProblemCodes.WATER_ALREADY_CONNECTED)));
        String label = label(source);
        rows.forEach(row -> publishConnected(actor, found.get(row.getPlotId()), row, label));
        return queries.source(source);
    }

    /** Ends the lasting connection of every plot to the source on {@code until} (all or nothing). */
    @Transactional
    WaterSource disconnectAll(String sourceId, List<UUID> plotIds, LocalDate until, AccessProfile actor) {
        MeterRecord source = resolve(sourceId, false);
        UUID meterId = source == null ? null : source.getId();
        Map<UUID, PlotFacts> found = lockPlots(plotIds);
        List<FieldProblem> problems = plotProblems(plotIds, found, false);
        Map<UUID, List<WaterConnectionRecord>> byPlot = problems.isEmpty() ? connectionsOf(plotIds) : Map.of();
        List<FieldProblem> notConnected = new ArrayList<>();
        List<WaterConnectionRecord> ending = new ArrayList<>();
        for (int i = 0; i < plotIds.size() && problems.isEmpty(); i++) {
            Optional<WaterConnectionRecord> lasting = byPlot.getOrDefault(plotIds.get(i), List.of()).stream()
                    .filter(WaterConnectionRecord::lasts)
                    .filter(row -> Objects.equals(row.getSourceMeterId(), meterId)).findFirst();
            if (lasting.isEmpty()) {
                notConnected.add(new FieldProblem(field(i), ProblemCodes.WATER_NOT_CONNECTED));
            } else if (until.isBefore(lasting.get().getValidFrom())) {
                problems.add(new FieldProblem(field(i), ProblemCodes.INVALID));
            } else {
                ending.add(lasting.get());
            }
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        if (!notConnected.isEmpty()) {
            throw notConnected(notConnected);
        }
        Instant now = clock.instant();
        ending.forEach(row -> row.endOn(until, now));
        connections.flush();
        String label = label(source);
        ending.forEach(row -> publishDisconnected(actor, found.get(row.getPlotId()), row, label));
        return queries.source(source);
    }

    /**
     * The meter a path names, or null for {@value WaterConnectionQueries#COOPERATIVE}: an unknown or malformed id is
     * 404 {@code NOT_FOUND}; a meter that is not a common consumer is 409 {@code WATER_SOURCE_INVALID}, and so is a removed
     * one when plots are to be connected to it.
     */
    private @Nullable MeterRecord resolve(String sourceId, boolean forConnection) {
        if (WaterConnectionQueries.COOPERATIVE.equals(sourceId)) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(sourceId);
        } catch (IllegalArgumentException e) {
            throw ApiProblemException.notFound();
        }
        MeterRecord meter = meters.findById(id).orElseThrow(ApiProblemException::notFound);
        if (meter.getKind() != MeterKind.COMMON_CONSUMER || (forConnection && !meter.isActive())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.WATER_SOURCE_INVALID,
                    "The water source is not an active common consumer meter.");
        }
        return meter;
    }

    /** The plots locked {@code FOR SHARE} in id order, by id; unknown ids are left out. */
    private Map<UUID, PlotFacts> lockPlots(List<UUID> plotIds) {
        return plots.findAllAndLockShared(new HashSet<>(plotIds)).stream()
                .collect(Collectors.toMap(PlotFacts::id, plot -> plot));
    }

    /** The field problems of the list: empty, a plot twice, unknown, and for a connection archived or common land. */
    private static List<FieldProblem> plotProblems(List<UUID> plotIds, Map<UUID, PlotFacts> found,
            boolean forConnection) {
        List<FieldProblem> problems = new ArrayList<>();
        if (plotIds.isEmpty()) {
            problems.add(new FieldProblem("plotIds", ProblemCodes.REQUIRED));
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < plotIds.size(); i++) {
            UUID plotId = plotIds.get(i);
            PlotFacts plot = found.get(plotId);
            if (!seen.add(plotId)) {
                problems.add(new FieldProblem(field(i), ProblemCodes.DUPLICATE));
            } else if (plot == null) {
                problems.add(new FieldProblem(field(i), ProblemCodes.UNKNOWN_PLOT));
            } else if (forConnection && plot.archived()) {
                problems.add(new FieldProblem(field(i), ProblemCodes.PLOT_ARCHIVED));
            } else if (forConnection && plot.commonLand()) {
                problems.add(new FieldProblem(field(i), ProblemCodes.PLOT_COMMON_LAND));
            }
        }
        return problems;
    }

    /** The connections of the plots locked {@code FOR UPDATE}, by plot. */
    private Map<UUID, List<WaterConnectionRecord>> connectionsOf(List<UUID> plotIds) {
        return connections.findByPlotIdsForUpdate(new HashSet<>(plotIds)).stream()
                .collect(Collectors.groupingBy(WaterConnectionRecord::getPlotId));
    }

    /** Whether a new connection from {@code from} would share a day with one of the rows or a lasting one. */
    private static boolean clashes(List<WaterConnectionRecord> rows, LocalDate from) {
        return rows.stream().anyMatch(row -> row.lasts() || !Objects.requireNonNull(row.getValidTo()).isBefore(from));
    }

    private void insert(List<WaterConnectionRecord> rows, List<FieldProblem> onClash) {
        try {
            connections.saveAll(rows);
            connections.flush();
        } catch (DataIntegrityViolationException e) {
            if (CONSTRAINTS.stream().anyMatch(constraint -> ConstraintViolations.violates(e, constraint))) {
                throw alreadyConnected(onClash);
            }
            throw e;
        }
    }

    private @Nullable MeterRecord sourceOf(WaterConnectionRecord row) {
        return row.getSourceMeterId() == null ? null : meters.findById(row.getSourceMeterId()).orElse(null);
    }

    private static boolean isActiveSource(MeterRecord meter) {
        return meter.getKind() == MeterKind.COMMON_CONSUMER && meter.isActive();
    }

    /** The source in the audit log: the meter's name (user content) or the code of the cooperative's water supply. */
    private static String label(@Nullable MeterRecord source) {
        return source == null ? SUPPLY_LABEL : source.auditLabel();
    }

    private void publishConnected(AccessProfile actor, PlotFacts plot, WaterConnectionRecord row, String source) {
        audit.publishWater(actor, WaterAction.WATER_CONNECTED, plot.id(), plot.number(), List.of(
                new FieldChange("source", null, source),
                new FieldChange("from", null, row.getValidFrom().toString())));
    }

    private void publishDisconnected(AccessProfile actor, PlotFacts plot, WaterConnectionRecord row, String source) {
        audit.publishWater(actor, WaterAction.WATER_DISCONNECTED, plot.id(), plot.number(), List.of(
                new FieldChange("source", null, source),
                new FieldChange("from", null, row.getValidFrom().toString()),
                new FieldChange("until", null, Objects.requireNonNull(row.getValidTo()).toString())));
    }

    private static String field(int index) {
        return "plotIds[" + index + "]";
    }

    private static ApiProblemException alreadyConnected(List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.WATER_ALREADY_CONNECTED,
                "The plot is already connected to water on one of these days.", errors);
    }

    private static ApiProblemException notConnected(List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.WATER_NOT_CONNECTED,
                "The plot has no lasting connection to this water source.", errors);
    }

    private static ApiProblemException invalid(List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", errors);
    }
}
