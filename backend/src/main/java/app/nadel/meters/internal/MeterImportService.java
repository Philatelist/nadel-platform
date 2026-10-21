package app.nadel.meters.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.meters.InvalidMeterValueException;
import app.nadel.meters.MeterImport;
import app.nadel.meters.MeterImportAction;
import app.nadel.meters.MeterImportItem;
import app.nadel.meters.MeterImportPlan;
import app.nadel.meters.MeterImportProblem;
import app.nadel.meters.MeterImportRow;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The meters module's side of the Excel import ({@link MeterImport}): plans the rows of the Meters sheet against the
 * plots of the file and the stored meters, locks what the plan relies on and registers the new plot meters. Plots are
 * read only through {@link PlotCatalog}. Every method runs in the caller's (the import's) transaction.
 */
@Service
class MeterImportService implements MeterImport {

    static final int MAX_SERIAL_LENGTH = 50;

    private static final String ACTIVE_INDEX = "meter_active_plot_uq";
    private static final String PLOT_FIELD = "plotNumber";
    private static final String RESOURCE_FIELD = "resource";
    private static final String SERIAL_FIELD = "serial";
    private static final String INSTALLED_FIELD = "installedOn";
    private static final String READING_FIELD = "initialReading";
    private static final String COMMON_KIND = "COMMON";

    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final MeterAuditPublisher audit;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    MeterImportService(MeterRepository meters, PlotCatalog plots, MeterAuditPublisher audit, Clock clock) {
        this.meters = meters;
        this.plots = plots;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the plot number of a meter row stands for. */
    private sealed interface Target {
    }

    /** No plot of the file and no active plot has the number. */
    private record Unknown() implements Target {
    }

    private record CommonLand() implements Target {
    }

    /** The plot's own row in the Plots sheet has a problem, which is reported there: nothing more to say. */
    private record Suppressed() implements Target {
    }

    /**
     * @param number  the plot number as the cooperative writes it (stored), or as the file writes it (a new plot)
     * @param created whether the same import creates the plot
     */
    private record Resolved(UUID plotId, String number, boolean created) implements Target {
    }

    /** The values of a row without problems. */
    private record Valid(String plotKey, MeterResource resource, String serial, LocalDate installedOn,
            MeterValue initial) {
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MeterImportPlan plan(UUID tenantId, UUID importId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<MeterImportRow> rows) {
        if (rows.isEmpty()) {
            return new MeterImportPlan(List.of());
        }
        Map<String, Target> targets = resolveTargets(plotRows, plotPlan, rows);
        Map<UUID, List<MeterRecord>> activeByPlot = activeMeters(targets);
        LocalDate today = LocalDate.now(clock);

        Set<String> seen = new HashSet<>();
        List<MeterImportItem> items = new ArrayList<>(rows.size());
        for (MeterImportRow row : rows) {
            List<MeterImportProblem> problems = new ArrayList<>();
            Valid valid = validate(row, today, problems);
            if (valid == null) {
                items.add(new MeterImportItem(row.row(), null, null, null, problems));
                continue;
            }
            if (!seen.add(valid.plotKey() + "/" + valid.resource())) {
                items.add(problem(row, RESOURCE_FIELD, ProblemCodes.DUPLICATE_IN_FILE, Map.of()));
                continue;
            }
            items.add(switch (targets.get(valid.plotKey())) {
                case Suppressed _ -> new MeterImportItem(row.row(), null, null, null, List.of());
                case Unknown _ -> problem(row, PLOT_FIELD, ProblemCodes.UNKNOWN_PLOT, Map.of());
                case CommonLand _ -> problem(row, PLOT_FIELD, ProblemCodes.PLOT_COMMON_LAND, Map.of());
                case Resolved plot -> planAgainstStored(row, valid, plot, activeByPlot, importId);
            });
        }
        return new MeterImportPlan(items);
    }

    /** The row's values, or null with the problems added to {@code problems}. */
    private static @Nullable Valid validate(MeterImportRow row, LocalDate today, List<MeterImportProblem> problems) {
        String plotNumber = blankToNull(row.plotNumber());
        if (plotNumber == null) {
            problems.add(new MeterImportProblem(PLOT_FIELD, ProblemCodes.REQUIRED));
        }
        MeterResource resource = null;
        String resourceText = blankToNull(row.resource());
        if (resourceText == null) {
            problems.add(new MeterImportProblem(RESOURCE_FIELD, ProblemCodes.REQUIRED));
        } else {
            resource = parseResource(resourceText);
            if (resource == null) {
                problems.add(new MeterImportProblem(RESOURCE_FIELD, ProblemCodes.INVALID));
            }
        }
        String serial = blankToNull(row.serial());
        if (serial == null) {
            problems.add(new MeterImportProblem(SERIAL_FIELD, ProblemCodes.REQUIRED));
        } else if (serial.codePointCount(0, serial.length()) > MAX_SERIAL_LENGTH) {
            problems.add(new MeterImportProblem(SERIAL_FIELD, ProblemCodes.TOO_LONG));
        }
        LocalDate installedOn = row.installedOn();
        if (installedOn == null) {
            problems.add(new MeterImportProblem(INSTALLED_FIELD, ProblemCodes.REQUIRED));
        } else if (installedOn.isAfter(today)) {
            problems.add(new MeterImportProblem(INSTALLED_FIELD, ProblemCodes.READING_DATE_IN_FUTURE));
        }
        MeterValue initial = null;
        if (row.initialReading() == null) {
            problems.add(new MeterImportProblem(READING_FIELD, ProblemCodes.REQUIRED));
        } else {
            try {
                initial = MeterValue.of(row.initialReading());
            } catch (InvalidMeterValueException e) {
                problems.add(new MeterImportProblem(READING_FIELD, ProblemCodes.METER_VALUE_INVALID));
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new Valid(key(plotNumber), resource, serial, installedOn, initial);
    }

    /** {@code NEW}, {@code UNCHANGED} or {@code METER_ACTIVE_EXISTS}, from the plot's active meter of the resource. */
    private static MeterImportItem planAgainstStored(MeterImportRow row, Valid valid, Resolved plot,
            Map<UUID, List<MeterRecord>> activeByPlot, UUID importId) {
        MeterRecord active = null;
        for (MeterRecord meter : activeByPlot.getOrDefault(plot.plotId(), List.of())) {
            if (meter.getResource() == valid.resource()) {
                active = meter;
            }
        }
        if (active == null) {
            return new MeterImportItem(row.row(), MeterImportAction.NEW,
                    newId(importId, valid.plotKey(), valid.resource()), plot.plotId(), List.of());
        }
        if (active.getSerial().strip().equalsIgnoreCase(valid.serial())) {
            return new MeterImportItem(row.row(), MeterImportAction.UNCHANGED, active.getId(), plot.plotId(),
                    List.of());
        }
        return problem(row, SERIAL_FIELD, ProblemCodes.METER_ACTIVE_EXISTS, Map.of("plot", plot.number()));
    }

    /**
     * The target of every plot number of the rows. The Plots sheet is asked first (its first row of the number decides,
     * a later duplicate is reported there): a file row may create the plot or change the kind of a stored one; only
     * then an active plot. An archived plot is never matched.
     */
    private Map<String, Target> resolveTargets(List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<MeterImportRow> rows) {
        Map<String, PlotImportItem> fileItems = new HashMap<>();
        Map<String, PlotImportRow> fileRows = new HashMap<>();
        for (int index = 0; index < plotRows.size(); index++) {
            String key = keyOrNull(plotRows.get(index).number());
            if (key != null && !fileItems.containsKey(key)) {
                fileItems.put(key, plotPlan.items().get(index));
                fileRows.put(key, plotRows.get(index));
            }
        }
        Set<String> numbers = new LinkedHashSet<>();
        for (MeterImportRow row : rows) {
            String key = keyOrNull(row.plotNumber());
            if (key != null) {
                numbers.add(key);
            }
        }
        Map<String, PlotFacts> stored = new HashMap<>();
        for (PlotFacts plot : plots.findActiveByNumbers(numbers)) {
            stored.put(key(plot.number()), plot);
        }

        Map<String, Target> targets = new HashMap<>();
        for (String key : numbers) {
            PlotImportItem item = fileItems.get(key);
            PlotFacts plot = stored.get(key);
            if (item != null) {
                targets.put(key, ofFilePlot(item, fileRows.get(key), plot));
            } else if (plot == null) {
                targets.put(key, new Unknown());
            } else {
                targets.put(key, plot.commonLand() ? new CommonLand() : new Resolved(plot.id(), plot.number(), false));
            }
        }
        return targets;
    }

    private static Target ofFilePlot(PlotImportItem item, PlotImportRow row, @Nullable PlotFacts stored) {
        if (item.action() == null || item.plotId() == null) {
            return new Suppressed();
        }
        String kind = blankToNull(row.kind());
        boolean commonLand = kind == null ? stored != null && stored.commonLand() : COMMON_KIND.equalsIgnoreCase(kind);
        if (commonLand) {
            return new CommonLand();
        }
        boolean created = item.action() == PlotImportAction.NEW;
        return new Resolved(item.plotId(), created || stored == null ? row.number().strip() : stored.number(), created);
    }

    /** The active meters of the stored plots the rows name, by plot. */
    private Map<UUID, List<MeterRecord>> activeMeters(Map<String, Target> targets) {
        Set<UUID> plotIds = new HashSet<>();
        for (Target target : targets.values()) {
            if (target instanceof Resolved plot && !plot.created()) {
                plotIds.add(plot.plotId());
            }
        }
        Map<UUID, List<MeterRecord>> byPlot = new HashMap<>();
        if (!plotIds.isEmpty()) {
            for (MeterRecord meter : meters.findActiveOfPlots(plotIds)) {
                byPlot.computeIfAbsent(meter.getPlotId(), id -> new ArrayList<>()).add(meter);
            }
        }
        return byPlot;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(MeterImportPlan plan) {
        Set<UUID> plotIds = new HashSet<>();
        Set<UUID> unchanged = new HashSet<>();
        for (MeterImportItem item : plan.items()) {
            if (item.action() == MeterImportAction.NEW) {
                plotIds.add(item.plotId());
            } else if (item.action() == MeterImportAction.UNCHANGED) {
                unchanged.add(item.meterId());
            }
        }
        // plots the same import creates are not there yet, so they are simply not found
        if (!plotIds.isEmpty()) {
            plots.findAllAndLockShared(plotIds);
        }
        if (!unchanged.isEmpty()) {
            meters.findAllByIdForUpdate(unchanged); // at most the 2,000 rows of the sheet: one statement
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuditEvent> apply(UUID tenantId, List<MeterImportRow> rows, MeterImportPlan plan, Actor actor) {
        Instant now = clock.instant();
        List<MeterRecord> created = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            MeterImportItem item = plan.items().get(index);
            if (item.action() != MeterImportAction.NEW) {
                continue;
            }
            MeterImportRow row = rows.get(index);
            MeterRecord meter = MeterRecord.register(item.meterId(), tenantId, MeterKind.PLOT,
                    parseResource(row.resource().strip()), item.plotId(), row.serial().strip(), null,
                    row.installedOn(), MeterValue.of(row.initialReading()), null, now);
            entityManager.persist(meter);
            created.add(meter);
        }
        if (created.isEmpty()) {
            return List.of();
        }
        try {
            meters.flush();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, ACTIVE_INDEX)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                        "The meters changed after the preview; upload the file again.");
            }
            throw e;
        }

        Map<UUID, PlotFacts> plotFacts = plots.findAll(created.stream().map(MeterRecord::getPlotId).toList());
        List<AuditEvent> events = new ArrayList<>(created.size());
        for (MeterRecord meter : created) {
            PlotFacts plot = plotFacts.get(meter.getPlotId());
            events.add(audit.event(tenantId, actor, "METER_REGISTERED", meter, MeterSnapshot.diff(MeterSnapshot.NONE,
                    MeterSnapshot.of(meter, plot == null ? null : plot.number()))));
        }
        return events;
    }

    private static MeterImportItem problem(MeterImportRow row, String field, String code, Map<String, String> params) {
        return new MeterImportItem(row.row(), null, null, null, List.of(new MeterImportProblem(field, code, params)));
    }

    /** {@code ELECTRICITY} or {@code WATER} in any letter case; null for anything else. */
    private static @Nullable MeterResource parseResource(String text) {
        String upper = text.strip().toUpperCase(Locale.ROOT);
        for (MeterResource resource : MeterResource.values()) {
            if (resource.name().equals(upper)) {
                return resource;
            }
        }
        return null;
    }

    /** The id of a new meter: derived from the import, the plot and the resource, so a second planning agrees. */
    private static UUID newId(UUID importId, String plotKey, MeterResource resource) {
        return UUID.nameUUIDFromBytes((importId + "/meter/" + plotKey + "/" + resource.name())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String number) {
        return number.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String keyOrNull(@Nullable String number) {
        String stripped = blankToNull(number);
        return stripped == null ? null : key(stripped);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
