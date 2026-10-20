package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans the plot rows of an Excel import. Reads with a handful of bulk queries (plots by number, plots by cadastral
 * number, plots with ownership history), never one query per row, and writes nothing.
 * <p>
 * Rules, in addition to those of {@link PlotData}: a number that appears twice in the file is a problem on the later
 * row; a cadastral number held by another plot (archived ones included) or claimed by an earlier row is taken; a plot
 * with ownership history cannot become common land. A blank value leaves the value of the matched plot unchanged; the
 * number of a matched plot keeps its stored spelling.
 */
@Service
class PlotImportService implements PlotImport {

    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;

    private static final String NUMBER_INDEX = "plot_tenant_number_uq";
    private static final String CADASTRAL_INDEX = "plot_tenant_cadastral_uq";
    private static final String OVERLAP_CONSTRAINT = "ownership_no_overlap_ex";

    private final PlotRepository plots;
    private final PlotStatusRepository statuses;
    private final OwnershipRepository ownerships;
    private final OwnershipImportPlanner ownershipPlanner;
    private final OwnershipRecorder recorder;
    private final PlotAuditPublisher auditPublisher;
    private final MemberDirectory members;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    PlotImportService(PlotRepository plots, PlotStatusRepository statuses, OwnershipRepository ownerships,
            OwnershipImportPlanner ownershipPlanner, OwnershipRecorder recorder, PlotAuditPublisher audit,
            MemberDirectory members, Clock clock) {
        this.plots = plots;
        this.statuses = statuses;
        this.ownerships = ownerships;
        this.ownershipPlanner = ownershipPlanner;
        this.recorder = recorder;
        this.auditPublisher = audit;
        this.members = members;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OwnershipImportPlan planOwnership(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<OwnershipImportRow> rows) {
        return ownershipPlanner.plan(tenantId, plotRows, plotPlan, rows);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlotImportPlan plan(UUID tenantId, UUID importId, List<PlotImportRow> rows) {
        Map<String, Plot> existingByNumber = activePlotsByNumber(tenantId, rows);
        Map<String, UUID> cadastralHolders = cadastralHolders(tenantId, rows);
        Set<UUID> withHistory = plotsWithHistory(rows, existingByNumber);

        Set<String> seenNumbers = new HashSet<>();
        Set<String> claimedCadastral = new HashSet<>();
        List<PlotImportItem> items = new ArrayList<>(rows.size());
        for (PlotImportRow row : rows) {
            items.add(planRow(row, importId, existingByNumber, cadastralHolders, withHistory, seenNumbers,
                    claimedCadastral));
        }
        return new PlotImportPlan(items);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(PlotImportPlan plotPlan, OwnershipImportPlan ownershipPlan) {
        Set<UUID> created = createdIds(plotPlan);
        Set<UUID> ids = new TreeSet<>(UuidOrder.POSTGRES);
        ids.addAll(existingIds(plotPlan, ownershipPlan, created, true));
        for (List<UUID> chunk : chunks(List.copyOf(ids))) {
            plots.lockAllForUpdate(chunk);
        }
    }

    /**
     * New plots are persisted with the ids of the plan (no SELECT per row, inserts batched by Hibernate); matched
     * plots and their status rows are loaded with a few bulk queries. A plan that no longer holds, or a unique or
     * overlap violation at a flush, means the data changed since the plan: 409 {@code IMPORT_STALE}.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuditEvent> apply(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<OwnershipImportRow> ownershipRows, OwnershipImportPlan ownershipPlan, Actor actor) {
        Instant now = clock.instant();
        List<AuditEvent> auditEntries = new ArrayList<>();
        Set<UUID> createdIds = createdIds(plotPlan);
        Map<UUID, Plot> existing = loadPlots(existingIds(plotPlan, ownershipPlan, createdIds, false));
        Map<UUID, Plot> created = new LinkedHashMap<>();
        for (int index = 0; index < plotRows.size(); index++) {
            PlotImportItem item = plotPlan.items().get(index);
            PlotImportRow row = plotRows.get(index);
            if (item.action() == PlotImportAction.NEW) {
                Plot plot = Plot.create(item.plotId(), tenantId, dataOf(row, null), now);
                entityManager.persist(plot);
                created.put(plot.getId(), plot);
                auditEntries.add(auditPublisher.event(tenantId, actor, "PLOT_CREATED", plot,
                        PlotSnapshot.diff(PlotSnapshot.NONE, PlotSnapshot.of(plot))));
            } else if (item.action() == PlotImportAction.UPDATE) {
                Plot plot = existing.get(item.plotId());
                if (plot == null || plot.getArchivedAt() != null) {
                    throw stale();
                }
                PlotSnapshot before = PlotSnapshot.of(plot);
                plot.apply(dataOf(row, plot));
                List<FieldChange> changes = PlotSnapshot.diff(before, PlotSnapshot.of(plot));
                if (!changes.isEmpty()) {
                    plot.touch(now);
                    auditEntries.add(auditPublisher.event(tenantId, actor, "PLOT_UPDATED", plot, changes));
                }
            }
        }
        // the plots reach the database before the rows that refer to them
        flush(plots::flush);
        applyOwnership(tenantId, ownershipRows, ownershipPlan, actor, now, created, existing, auditEntries);
        return auditEntries;
    }

    /** Records the owners of every FIRST_OWNERS group and the status rows of the new plots; adds the audit events to {@code auditEntries}. */
    private void applyOwnership(UUID tenantId, List<OwnershipImportRow> rows, OwnershipImportPlan plan, Actor actor,
            Instant now, Map<UUID, Plot> created, Map<UUID, Plot> existing, List<AuditEvent> auditEntries) {
        Map<UUID, List<OwnershipImportRow>> groups = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            OwnershipImportItem item = plan.items().get(index);
            if (item.outcome() == OwnershipImportOutcome.FIRST_OWNERS) {
                groups.computeIfAbsent(item.plotId(), id -> new ArrayList<>()).add(rows.get(index));
            }
        }

        Map<UUID, PlotStatusEntry> entries = new HashMap<>();
        statuses.findAllById(existing.keySet()).forEach(entry -> entries.put(entry.getPlotId(), entry));
        List<PlotStatusEntry> inserted = new ArrayList<>();
        for (Plot plot : created.values()) {
            PlotStatusEntry entry = PlotStatusEntry.initial(plot.getId(), tenantId);
            entries.put(plot.getId(), entry);
            inserted.add(entry);
        }
        Map<UUID, MemberRef> names = members.find(groups.values().stream()
                .flatMap(List::stream)
                .map(OwnershipImportRow::memberId)
                .distinct()
                .toList());

        for (Map.Entry<UUID, List<OwnershipImportRow>> group : groups.entrySet()) {
            Plot plot = created.containsKey(group.getKey()) ? created.get(group.getKey())
                    : existing.get(group.getKey());
            if (plot == null || plot.getArchivedAt() != null || plot.getKind() != PlotKind.PLOT) {
                throw stale();
            }
            List<OwnershipRecorder.Owner> owners = new ArrayList<>();
            for (OwnershipImportRow row : group.getValue()) {
                MemberRef member = names.get(row.memberId());
                if (member == null || member.archived()) {
                    throw stale();
                }
                owners.add(new OwnershipRecorder.Owner(row.memberId(),
                        OwnershipImportPlanner.parseShare(row.share().strip())));
            }
            // every row of a plot carries one date (checked by the plan)
            LocalDate from = group.getValue().getFirst().validFrom();
            recorder.start(tenantId, plot.getId(), owners, from, null, now).forEach(entityManager::persist);

            PlotStatusEntry entry = entries.get(plot.getId());
            if (entry == null) {
                entry = PlotStatusEntry.initial(plot.getId(), tenantId);
                entries.put(plot.getId(), entry);
                inserted.add(entry);
            }
            recorder.refreshStatus(entry, true, now);
            String newOwners = OwnershipRecorder.ownersText(owners.stream()
                    .map(o -> OwnershipRecorder.text(names.get(o.memberId()), o.share())));
            auditEntries.add(recorder.changeEvent(tenantId, actor, plot, from, null, newOwners, null));
        }
        inserted.forEach(entityManager::persist);
        flush(ownerships::flush);
    }

    /** The plot data of a row, with the same overlay and rules as the plan; a problem means the plan is stale. */
    private static PlotData dataOf(PlotImportRow row, @Nullable Plot existing) {
        Resolved resolved = resolve(row, existing, blankToNull(row.number()));
        if (!resolved.problems().isEmpty() || resolved.validation().data() == null) {
            throw stale();
        }
        return resolved.validation().data();
    }

    /** The ids of the plots the plan creates. */
    private static Set<UUID> createdIds(PlotImportPlan plan) {
        Set<UUID> ids = new HashSet<>();
        plan.items().stream()
                .filter(item -> item.action() == PlotImportAction.NEW)
                .forEach(item -> ids.add(item.plotId()));
        return ids;
    }

    /**
     * The stored plots a plan touches: those it updates (also the unchanged ones when {@code withUnchanged}) and those
     * that get first owners.
     */
    private static Set<UUID> existingIds(PlotImportPlan plotPlan, OwnershipImportPlan ownershipPlan,
            Set<UUID> created, boolean withUnchanged) {
        Set<UUID> ids = new HashSet<>();
        for (PlotImportItem item : plotPlan.items()) {
            if (item.action() == PlotImportAction.UPDATE
                    || withUnchanged && item.action() == PlotImportAction.UNCHANGED) {
                ids.add(item.plotId());
            }
        }
        for (OwnershipImportItem item : ownershipPlan.items()) {
            if (item.outcome() == OwnershipImportOutcome.FIRST_OWNERS && !created.contains(item.plotId())) {
                ids.add(item.plotId());
            }
        }
        return ids;
    }

    private Map<UUID, Plot> loadPlots(Set<UUID> ids) {
        Map<UUID, Plot> byId = new HashMap<>();
        for (List<UUID> chunk : chunks(List.copyOf(ids))) {
            for (Plot plot : plots.findAllById(chunk)) {
                byId.put(plot.getId(), plot);
            }
        }
        return byId;
    }

    /** Runs a flush; a rule broken by a record created meanwhile (number, cadastral number, overlap) is stale. */
    private static void flush(Runnable flush) {
        try {
            flush.run();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, NUMBER_INDEX) || ConstraintViolations.violates(e, CADASTRAL_INDEX)
                    || ConstraintViolations.violates(e, OVERLAP_CONSTRAINT)) {
                throw stale();
            }
            throw e;
        }
    }

    private static ApiProblemException stale() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                "The plots or owners changed after the preview; upload the file again.");
    }

    private PlotImportItem planRow(PlotImportRow row, UUID importId, Map<String, Plot> existingByNumber,
            Map<String, UUID> cadastralHolders, Set<UUID> withHistory, Set<String> seenNumbers,
            Set<String> claimedCadastral) {
        String number = blankToNull(row.number());
        String key = number == null ? null : lower(number);
        if (key != null && !seenNumbers.add(key)) {
            return problem(row, List.of(new FieldProblem("number", ProblemCodes.DUPLICATE_IN_FILE)));
        }
        Plot existing = key == null ? null : existingByNumber.get(key);

        Resolved resolved = resolve(row, existing, number);
        List<FieldProblem> problems = resolved.problems();
        PlotKind kind = resolved.kind();
        PlotData.Validation validation = resolved.validation();

        checkCadastral(row, existing, cadastralHolders, claimedCadastral, problems);
        if (existing != null && existing.getKind() == PlotKind.PLOT && kind == PlotKind.COMMON
                && withHistory.contains(existing.getId())) {
            problems.add(new FieldProblem("kind", ProblemCodes.PLOT_HAS_OWNERSHIP_HISTORY));
        }

        List<FieldProblem> distinct = onePerField(problems);
        if (!distinct.isEmpty()) {
            return problem(row, distinct);
        }
        PlotData data = validation.data();
        if (existing == null) {
            return new PlotImportItem(row.row(), PlotImportAction.NEW, newId(importId, key), List.of(), List.of());
        }
        List<String> changed = PlotSnapshot.diff(PlotSnapshot.of(existing), PlotSnapshot.of(data)).stream()
                .map(FieldChange::field)
                .toList();
        return new PlotImportItem(row.row(),
                changed.isEmpty() ? PlotImportAction.UNCHANGED : PlotImportAction.UPDATE,
                existing.getId(), changed, List.of());
    }


    /** What the row says about a plot, with a blank cell keeping the stored value of {@code existing}. */
    private record Resolved(PlotData.Validation validation, @Nullable PlotKind kind, List<FieldProblem> problems) {
    }

    /**
     * Applies the blank-keeps-existing rule to a row, then the rules of {@link PlotData}. The problems found are
     * returned together with the validation (data present only when there are none). Shared by the plan and by the
     * apply, so both read a row the same way.
     */
    private static Resolved resolve(PlotImportRow row, @Nullable Plot existing, @Nullable String number) {
        List<FieldProblem> problems = new ArrayList<>();
        PlotKind kind = null;
        String kindText = blankToNull(row.kind());
        if (kindText != null) {
            kind = parseKind(kindText);
            if (kind == null) {
                problems.add(new FieldProblem("kind", ProblemCodes.INVALID));
            }
        }
        boolean kindGiven = kindText != null;
        if (!kindGiven && existing != null) {
            kind = existing.getKind();
        }

        BigDecimal area = row.areaM2();
        if (area == null) {
            if (existing != null) {
                area = existing.getAreaM2();
            } else if (kind == PlotKind.COMMON) {
                area = BigDecimal.ZERO;
            } else {
                problems.add(new FieldProblem("areaM2", ProblemCodes.AREA_BOTH_OR_NONE));
            }
        }

        String street = firstNonBlank(row.street(), existing == null ? null : existing.getStreet());
        String cadastral = firstNonBlank(row.cadastralNumber(), existing == null ? null : existing.getCadastralNumber());
        String notes = firstNonBlank(row.notes(), existing == null ? null : existing.getNotes());
        boolean hasBuilding = row.hasBuilding() != null ? row.hasBuilding()
                : existing != null && existing.hasBuilding();
        // a matched plot keeps its stored spelling of the number
        String effectiveNumber = existing != null ? existing.getNumber() : number;

        PlotData.Validation validation = PlotData.tryFrom(effectiveNumber, street, kind, area, cadastral,
                hasBuilding, notes);
        problems.addAll(validation.problems());
        return new Resolved(validation, kind, problems);
    }

    /**
     * A cadastral number the row gives must not belong to another plot: not in the database (archived plots keep
     * theirs) and not to an earlier row of the file.
     */
    private static void checkCadastral(PlotImportRow row, @Nullable Plot existing,
            Map<String, UUID> cadastralHolders, Set<String> claimedCadastral, List<FieldProblem> problems) {
        Optional<CadastralNumber> parsed = parseCadastral(row.cadastralNumber());
        if (parsed.isEmpty()) {
            return;
        }
        String cadastral = parsed.get().value();
        UUID holder = cadastralHolders.get(cadastral);
        boolean heldByAnother = holder != null && (existing == null || !holder.equals(existing.getId()));
        // a row with the same plot number never gets here twice (duplicates stop earlier), so a repeat is another plot
        boolean claimedByEarlierRow = !claimedCadastral.add(cadastral);
        if (heldByAnother || claimedByEarlierRow) {
            problems.add(new FieldProblem("cadastralNumber", ProblemCodes.CADASTRAL_NUMBER_TAKEN));
        }
    }

    private Map<String, Plot> activePlotsByNumber(UUID tenantId, List<PlotImportRow> rows) {
        List<String> numbers = rows.stream()
                .map(row -> blankToNull(row.number()))
                .filter(Objects::nonNull)
                .map(PlotImportService::lower)
                .distinct()
                .toList();
        Map<String, Plot> byNumber = new HashMap<>();
        for (List<String> chunk : chunks(numbers)) {
            for (Plot plot : plots.findActiveByLowerNumbers(tenantId, chunk)) {
                byNumber.put(lower(plot.getNumber()), plot);
            }
        }
        return byNumber;
    }

    private Map<String, UUID> cadastralHolders(UUID tenantId, List<PlotImportRow> rows) {
        List<String> numbers = rows.stream()
                .map(row -> parseCadastral(row.cadastralNumber()))
                .flatMap(Optional::stream)
                .map(CadastralNumber::value)
                .distinct()
                .toList();
        Map<String, UUID> holders = new HashMap<>();
        for (List<String> chunk : chunks(numbers)) {
            for (Plot plot : plots.findByCadastralNumbers(tenantId, chunk)) {
                holders.put(plot.getCadastralNumber(), plot.getId());
            }
        }
        return holders;
    }

    /** Ids of the matched plots that have ever had an owner: only needed for a plot that might become common land. */
    private Set<UUID> plotsWithHistory(List<PlotImportRow> rows, Map<String, Plot> existingByNumber) {
        List<UUID> candidates = rows.stream()
                .filter(row -> row.kind() != null && parseKind(row.kind().strip()) == PlotKind.COMMON)
                .map(row -> row.number() == null ? null : existingByNumber.get(lower(row.number().strip())))
                .filter(plot -> plot != null && plot.getKind() == PlotKind.PLOT)
                .map(Plot::getId)
                .distinct()
                .toList();
        Set<UUID> withHistory = new HashSet<>();
        for (List<UUID> chunk : chunks(candidates)) {
            withHistory.addAll(ownerships.findPlotIdsWithOwnership(chunk));
        }
        return withHistory;
    }

    private static Optional<CadastralNumber> parseCadastral(@Nullable String text) {
        return text == null ? Optional.empty() : CadastralNumber.parse(text);
    }

    private static PlotImportItem problem(PlotImportRow row, List<FieldProblem> problems) {
        return new PlotImportItem(row.row(), null, null, List.of(), problems);
    }

    /** One problem per field, the first one found: a field reported twice (say a missing area) shows once. */
    private static List<FieldProblem> onePerField(List<FieldProblem> problems) {
        Set<String> fields = new HashSet<>();
        return problems.stream().filter(problem -> fields.add(problem.field())).toList();
    }

    static @Nullable PlotKind parseKind(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "PLOT" -> PlotKind.PLOT;
            case "COMMON" -> PlotKind.COMMON;
            default -> null;
        };
    }

    /** A new plot gets the same id on every planning of the same import, so a re-plan equals the preview. */
    private static UUID newId(UUID importId, String key) {
        return UUID.nameUUIDFromBytes((importId + "/plot/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String firstNonBlank(@Nullable String preferred, @Nullable String fallback) {
        String value = blankToNull(preferred);
        return value != null ? value : fallback;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static <T> List<List<T>> chunks(List<T> list) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            chunks.add(list.subList(from, Math.min(list.size(), from + CHUNK)));
        }
        return chunks;
    }
}
