package app.nadel.tariffs.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.money.InvalidRateException;
import app.nadel.money.Rate;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.TariffVersionUsage;
import app.nadel.tariffs.VersionTerms;
import app.nadel.tariffs.VersionTerms.Violation;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffCreate;
import nadel.contract.model.TariffExclusionUpdate;
import nadel.contract.model.TariffScopeUpdate;
import nadel.contract.model.TariffVersion;
import nadel.contract.model.TariffVersionCreate;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates tariffs, adds versions and corrects or deletes them. Every method is one transaction that also publishes the
 * audit event. The cooperative always comes from the caller's {@link AccessProfile}, never from the request; the caller
 * must hold {@code TARIFFS_VIEW} and {@code TARIFFS_MANAGE} (checked on the controller).
 * <p>
 * The charge type is locked {@code FOR SHARE} first, so a concurrent archive of it waits for this transaction (or this
 * one sees it archived). A version is corrected or deleted only while no charge uses it
 * ({@link TariffVersionUsage}); the tariff row is locked {@code FOR UPDATE} first, so the use check and the change
 * cannot interleave with another correction of the same tariff. A correction of the note alone needs no use check: it
 * changes no amount. The rate is never rounded; {@link Rate#parse} refuses what does not fit, and the one-off rule is
 * that of {@link VersionTerms}.
 * <p>
 * The changes themselves ({@link #add}, {@link #update}, {@link #delete}) run in the caller's transaction and return
 * the audit entry unpublished, so {@link TariffVersionChangeService} (a version change booked with a recalculation,
 * feature 012) applies them with the same locks and validation, without the use check.
 */
@Service
class TariffCommands {

    private static final String DATE_INDEX = "tariff_version_valid_from_uq";
    /** The audited fields of a version, in the order they are written. */
    private static final List<String> VERSION_FIELDS = List.of("validFrom", "basis", "frequency", "rate", "dueDate",
            "note");

    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final ChargeTypeRepository chargeTypes;
    private final PlotCatalog plots;
    private final TariffAssembler assembler;
    private final TariffAuditPublisher audit;
    private final EntityManager entityManager;
    private final Clock clock;
    private final List<TariffVersionUsage> usages;

    TariffCommands(TariffRepository tariffs, TariffVersionRepository versions, TariffScopePlotRepository scopePlots,
            ChargeTypeRepository chargeTypes, PlotCatalog plots, TariffAssembler assembler,
            TariffAuditPublisher audit, EntityManager entityManager, Clock clock, List<TariffVersionUsage> usages) {
        this.tariffs = tariffs;
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.chargeTypes = chargeTypes;
        this.plots = plots;
        this.assembler = assembler;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
        this.usages = usages;
    }

    @Transactional
    Tariff create(TariffCreate input, AccessProfile actor) {
        List<FieldProblem> problems = new ArrayList<>();
        String name = cleanName(input.getName(), problems);
        TariffVersionCreate first = input.getFirstVersion();
        VersionTerms terms = terms(first, "firstVersion.", problems);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }

        ChargeTypeRecord chargeType = lockChargeType(input.getChargeTypeId(), actor);
        Instant now = Instant.now();
        TariffRecord tariff = tariffs.saveAndFlush(
                TariffRecord.create(Objects.requireNonNull(actor.tenantId()), chargeType.getId(), name, now));
        String note = blankToNull(first.getNote());
        TariffVersionRecord version = saveVersion(
                TariffVersionRecord.create(actor.tenantId(), tariff.getId(), Objects.requireNonNull(terms), note, now));

        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("name", null, name));
        changes.add(new FieldChange("chargeType", null, ChargeTypeSnapshot.auditName(chargeType)));
        changes.addAll(versionFields(version, true));
        publish(actor, "TARIFF_CREATED", tariff, changes);
        return assembler.assemble(tariff, chargeType);
    }

    @Transactional
    TariffVersion addVersion(UUID tariffId, TariffVersionCreate input, AccessProfile actor) {
        VersionTerms terms = validTerms(input);
        TariffRecord tariff = tariffs.findById(tariffId).filter(t -> t.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
        Applied applied = add(tariff, terms, input, actor);
        publish(applied);
        return versionDto(tariff, applied.version());
    }

    /**
     * Replaces the terms of a version (the same fields and rules as a new version). Order: field problems (400); the
     * tariff row {@code FOR UPDATE} and its charge type {@code FOR SHARE} (404, 409 {@code CHARGE_TYPE_ARCHIVED}); the
     * version of that tariff (404); the use check (409 {@code TARIFF_VERSION_IN_USE}), unless the request changes no
     * term (only the note, or nothing: a correction that changes no amount, feature 012 §2.4); the write (409
     * {@code TARIFF_VERSION_DATE_TAKEN}). A request that changes nothing writes nothing and no audit entry. The end
     * dates of the neighbours are derived on read, so nothing else is stored.
     */
    @Transactional
    TariffVersion updateVersion(UUID tariffId, UUID versionId, TariffVersionCreate input, AccessProfile actor) {
        VersionTerms terms = validTerms(input);
        TariffRecord tariff = lockTariff(tariffId, actor);
        lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = version(tariff, versionId);
        String note = blankToNull(input.getNote());
        if (changesTerms(version, terms, note)) {
            checkUnused(version, actor);
        }
        Applied applied = update(tariff, version, terms, note, actor);
        publish(applied);
        return versionDto(tariff, version);
    }

    /**
     * Deletes a version. Same order as {@link #updateVersion}, the use check always; the only version of a tariff is
     * refused (409 {@code TARIFF_LAST_VERSION}), because a tariff always has a version. The version is removed from
     * the table (no charge uses it). The audit entry holds the old values.
     */
    @Transactional
    void deleteVersion(UUID tariffId, UUID versionId, AccessProfile actor) {
        TariffRecord tariff = lockTariff(tariffId, actor);
        lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = version(tariff, versionId);
        checkUnused(version, actor);
        publish(delete(tariff, version, false, actor));
    }

    // ---- the changes themselves, shared with TariffVersionChanges (feature 012): in the caller's transaction

    /**
     * A version change applied (and flushed) in the caller's transaction.
     *
     * @param event the audit entry of the change, not published; null when an update changed nothing
     * @param from  the earliest first day of the old and the new validity of the version
     * @param to    the latest last day of the two; null when either runs on without end
     */
    record Applied(TariffRecord tariff, TariffVersionRecord version, @Nullable AuditEvent event, LocalDate from,
            @Nullable LocalDate to) {
    }

    /** A validity of a version: from its start to the day before the next version starts (null: no end). */
    private record Validity(LocalDate from, @Nullable LocalDate to) {

        Applied span(TariffRecord tariff, TariffVersionRecord version, @Nullable AuditEvent event,
                @Nullable Validity other) {
            if (other == null) {
                return new Applied(tariff, version, event, from, to);
            }
            LocalDate start = from.isBefore(other.from()) ? from : other.from();
            LocalDate end = to == null || other.to() == null ? null : to.isAfter(other.to()) ? to : other.to();
            return new Applied(tariff, version, event, start, end);
        }
    }

    /** The validity of the version among the stored versions of its tariff, as stored now. */
    private Validity validity(TariffVersionRecord version) {
        LocalDate from = version.getValidFrom();
        LocalDate next = versions.findByTariffIdOrderByValidFromAsc(version.getTariffId()).stream()
                .filter(other -> !other.getId().equals(version.getId()) && other.getValidFrom().isAfter(from))
                .map(TariffVersionRecord::getValidFrom).findFirst().orElse(null);
        return new Validity(from, next == null ? null : next.minusDays(1));
    }

    /** The terms of the request; 400 {@code VALIDATION_FAILED} with every field problem. */
    static VersionTerms validTerms(TariffVersionCreate input) {
        List<FieldProblem> problems = new ArrayList<>();
        VersionTerms terms = terms(input, "", problems);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        return Objects.requireNonNull(terms);
    }

    /** Adds a version to the tariff (its charge type locked {@code FOR SHARE} first). */
    Applied add(TariffRecord tariff, VersionTerms terms, TariffVersionCreate input, AccessProfile actor) {
        lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = saveVersion(TariffVersionRecord.create(Objects.requireNonNull(actor.tenantId()),
                tariff.getId(), terms, blankToNull(input.getNote()), Instant.now()));
        return validity(version).span(tariff, version,
                event(actor, "TARIFF_VERSION_ADDED", tariff, versionFields(version, true)), null);
    }

    /**
     * Corrects the version (the tariff and its charge type are locked); a correction that changes nothing writes
     * nothing and has no audit entry.
     */
    Applied update(TariffRecord tariff, TariffVersionRecord version, VersionTerms terms, @Nullable String note,
            AccessProfile actor) {
        Validity before = validity(version);
        List<FieldChange> old = versionFields(version, false);
        version.correct(terms, note);
        List<FieldChange> changes = changedFields(old, versionFields(version, false));
        if (changes.isEmpty()) {
            return before.span(tariff, version, null, null);
        }
        flushVersion(version);
        return validity(version).span(tariff, version, event(actor, "TARIFF_VERSION_UPDATED", tariff, changes),
                before);
    }

    /**
     * Deletes the version (the tariff and its charge type are locked): softly for a recalculation, from the table
     * otherwise; the only version of a tariff is 409 {@code TARIFF_LAST_VERSION}.
     */
    Applied delete(TariffRecord tariff, TariffVersionRecord version, boolean softly, AccessProfile actor) {
        if (versions.countByTariffId(tariff.getId()) <= 1) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TARIFF_LAST_VERSION,
                    "The only version of a tariff cannot be deleted.");
        }
        Validity before = validity(version);
        List<FieldChange> old = versionFields(version, false);
        if (softly) {
            version.deleteSoftly(Instant.now());
            versions.saveAndFlush(version);
        } else {
            versions.delete(version);
            versions.flush();
        }
        return before.span(tariff, version, event(actor, "TARIFF_VERSION_DELETED", tariff, old.stream()
                .filter(change -> change.newValue() != null)
                .map(change -> new FieldChange(change.field(), change.newValue(), null)).toList()), null);
    }

    /** Whether the request changes a term of the version (anything but the note). */
    private static boolean changesTerms(TariffVersionRecord version, VersionTerms terms, @Nullable String note) {
        List<FieldChange> after = versionFields(TariffVersionRecord.create(version.getTenantId(),
                version.getTariffId(), terms, note, Instant.EPOCH), false);
        return changedFields(versionFields(version, false), after).stream()
                .anyMatch(change -> !"note".equals(change.field()));
    }

    /** The tariff of the caller's cooperative, locked {@code FOR UPDATE}; 404 when missing or foreign. */
    TariffRecord lockTariff(UUID id, AccessProfile actor) {
        return tariffs.findByIdForUpdate(id).filter(t -> t.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
    }

    /** The version of that tariff; 404 when it is missing, deleted softly or belongs to another tariff. */
    TariffVersionRecord version(TariffRecord tariff, UUID versionId) {
        return versions.findById(versionId)
                .filter(v -> v.getTariffId().equals(tariff.getId()) && !v.isDeleted())
                .orElseThrow(ApiProblemException::notFound);
    }

    /** 409 {@code TARIFF_VERSION_IN_USE} when a charge uses the version. */
    private void checkUnused(TariffVersionRecord version, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        if (usages.stream().anyMatch(usage -> usage.isUsed(tenantId, version.getId()))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TARIFF_VERSION_IN_USE,
                    "A charge uses this version, so it can no longer be changed or deleted.");
        }
    }

    /** The answer for a version: its end date is derived from the versions that start after it. */
    private TariffVersion versionDto(TariffRecord tariff, TariffVersionRecord version) {
        List<TariffVersion> all = TariffDtoMapper.versionsNewestFirst(
                versions.findByTariffIdOrderByValidFromAsc(tariff.getId()), LocalDate.now(clock));
        return all.stream().filter(v -> v.getId().equals(version.getId())).findFirst().orElseThrow();
    }

    /** Fields whose value differs, as old to new; {@code before} and {@code after} come from the same fields list. */
    private static List<FieldChange> changedFields(List<FieldChange> before, List<FieldChange> after) {
        Map<String, String> old = new LinkedHashMap<>();
        before.forEach(change -> old.put(change.field(), change.newValue()));
        Map<String, String> current = new LinkedHashMap<>();
        after.forEach(change -> current.put(change.field(), change.newValue()));
        List<FieldChange> changes = new ArrayList<>();
        for (String field : VERSION_FIELDS) {
            String was = old.get(field);
            String is = current.get(field);
            if (!Objects.equals(was, is)) {
                changes.add(new FieldChange(field, was, is));
            }
        }
        return changes;
    }

    /**
     * Replaces the scope of the tariff. The tariff row is locked {@code FOR UPDATE} first (two scope changes of one
     * tariff run one after another) and its charge type {@code FOR SHARE}. The stored rows that no longer hold are
     * deleted and the new ones inserted; a save that changes nothing writes nothing, not even an audit entry. Reasons
     * go to the audit log only, never to the application log.
     */
    @Transactional
    Tariff updateScope(UUID id, TariffScopeUpdate input, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        TariffRecord tariff = tariffs.findByIdForUpdate(id).filter(t -> t.getTenantId().equals(tenantId))
                .orElseThrow(ApiProblemException::notFound);
        ChargeTypeRecord chargeType = lockChargeType(tariff.getChargeTypeId(), actor);
        List<TariffScopePlotRecord> stored = scopePlots.findByTariffId(id);

        ScopeKind kind = ScopeKind.valueOf(input.getKind().name());
        List<UUID> selectedIds = input.getPlotIds() == null ? List.of() : input.getPlotIds();
        List<TariffExclusionUpdate> exclusionInputs = input.getExclusions() == null ? List.of()
                : input.getExclusions();
        Set<UUID> requested = new HashSet<>(selectedIds);
        exclusionInputs.forEach(e -> requested.add(e.getPlotId()));
        stored.forEach(row -> requested.add(row.getPlotId()));
        Map<UUID, PlotFacts> facts = plots.findAll(requested);
        Set<UUID> storedSelected = storedPlots(stored, TariffScopePlotRecord.Kind.SELECTED);
        Set<UUID> storedExcluded = storedPlots(stored, TariffScopePlotRecord.Kind.EXCLUDED);

        List<FieldProblem> problems = new ArrayList<>();
        Set<UUID> newSelected = new LinkedHashSet<>();
        Map<UUID, String> newExcluded = new LinkedHashMap<>();
        if (kind == ScopeKind.ALL_PLOTS && !selectedIds.isEmpty()) {
            problems.add(new FieldProblem("plotIds", ProblemCodes.INVALID));
        } else {
            for (int i = 0; i < selectedIds.size(); i++) {
                UUID plotId = selectedIds.get(i);
                String field = "plotIds[" + i + "]";
                if (!newSelected.add(plotId)) {
                    problems.add(new FieldProblem(field, ProblemCodes.DUPLICATE));
                } else {
                    checkPlot(facts.get(plotId), storedSelected.contains(plotId), field,
                            problems);
                }
            }
        }
        if (kind == ScopeKind.SELECTED_PLOTS && !exclusionInputs.isEmpty()) {
            problems.add(new FieldProblem("exclusions", ProblemCodes.INVALID));
        } else {
            for (int i = 0; i < exclusionInputs.size(); i++) {
                TariffExclusionUpdate exclusion = exclusionInputs.get(i);
                UUID plotId = exclusion.getPlotId();
                String field = "exclusions[" + i + "]";
                String reason = exclusion.getReason() == null ? "" : exclusion.getReason().strip();
                if (reason.isEmpty()) {
                    problems.add(new FieldProblem(field + ".reason", ProblemCodes.REQUIRED));
                }
                if (newExcluded.containsKey(plotId)) {
                    problems.add(new FieldProblem(field + ".plotId", ProblemCodes.DUPLICATE));
                } else {
                    newExcluded.put(plotId, reason);
                    checkPlot(facts.get(plotId), storedExcluded.contains(plotId),
                            field + ".plotId", problems);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }

        TariffScopeChange change = new TariffScopeChange(tariff.getScopeKind(), stored, kind, newSelected,
                newExcluded);
        if (!change.isChange()) {
            return assembler.assemble(tariff, chargeType);
        }

        Instant now = Instant.now();
        scopePlots.deleteAll(stored.stream().filter(row -> !change.keeps(row)).toList());
        scopePlots.flush(); // a row with a new reason is deleted and inserted under the same key
        tariff.changeScope(kind, now);
        change.addedSelected().forEach(plotId -> entityManager
                .persist(TariffScopePlotRecord.selected(tenantId, id, plotId, now)));
        change.addedExclusions().forEach((plotId, reason) -> entityManager
                .persist(TariffScopePlotRecord.excluded(tenantId, id, plotId, reason, now)));
        entityManager.flush();

        publishScopeAudit(actor, tariff, change, facts);
        return assembler.assemble(tariff, chargeType);
    }

    /** A plot of the scope must exist, must not be common land, and must not be archived unless it was there already. */
    private static void checkPlot(@Nullable PlotFacts plot, boolean alreadyInScope, String field,
            List<FieldProblem> problems) {
        if (plot == null) {
            problems.add(new FieldProblem(field, ProblemCodes.UNKNOWN_PLOT));
        } else if (plot.commonLand()) {
            problems.add(new FieldProblem(field, ProblemCodes.TARIFF_COMMON_LAND));
        } else if (plot.archived() && !alreadyInScope) {
            problems.add(new FieldProblem(field, ProblemCodes.PLOT_ARCHIVED));
        }
    }

    private static Set<UUID> storedPlots(List<TariffScopePlotRecord> stored, TariffScopePlotRecord.Kind kind) {
        return stored.stream().filter(row -> row.getKind() == kind).map(TariffScopePlotRecord::getPlotId)
                .collect(Collectors.toSet());
    }

    /**
     * {@code TARIFF_SCOPE_CHANGED} when the kind or the selected plots changed (the kind, and the selected plot
     * numbers in natural order), then one {@code TARIFF_EXCLUSION_REMOVED} and one {@code TARIFF_EXCLUSION_ADDED}
     * per exclusion, in the order of the plot numbers. A new reason is a removal of the old exclusion plus an addition.
     */
    private void publishScopeAudit(AccessProfile actor, TariffRecord tariff,
            TariffScopeChange change, Map<UUID, PlotFacts> facts) {
        List<UUID> order = plots.inNaturalOrder(change.plots());
        Function<Set<UUID>, String> numbers = ids -> {
            String joined = order.stream().filter(ids::contains).map(plot -> facts.get(plot).number())
                    .collect(Collectors.joining(", "));
            return joined.isEmpty() ? null : joined;
        };

        if (change.kindChanged() || change.selectedChanged()) {
            List<FieldChange> changes = new ArrayList<>();
            if (change.kindChanged()) {
                changes.add(new FieldChange("scope", change.oldKind().name(), change.newKind().name()));
            }
            if (change.selectedChanged()) {
                changes.add(new FieldChange("selectedPlots", numbers.apply(change.oldSelected()),
                        numbers.apply(change.newSelected())));
            }
            publish(actor, "TARIFF_SCOPE_CHANGED", tariff, changes);
        }
        Map<UUID, String> removed = change.removedExclusions();
        Map<UUID, String> added = change.addedExclusions();
        for (UUID plot : order) {
            if (removed.containsKey(plot)) {
                publish(actor, "TARIFF_EXCLUSION_REMOVED", tariff, List.of(
                        new FieldChange("plot", facts.get(plot).number(), null),
                        new FieldChange("reason", removed.get(plot), null)));
            }
        }
        for (UUID plot : order) {
            if (added.containsKey(plot)) {
                publish(actor, "TARIFF_EXCLUSION_ADDED", tariff, List.of(
                        new FieldChange("plot", null, facts.get(plot).number()),
                        new FieldChange("reason", null, added.get(plot))));
            }
        }
    }

    /** The charge type of the caller's cooperative, locked {@code FOR SHARE}; 404 when missing, 409 when archived. */
    ChargeTypeRecord lockChargeType(UUID id, AccessProfile actor) {
        ChargeTypeRecord type = chargeTypes.findByIdForShare(id).filter(t -> t.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
        if (type.getArchivedAt() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CHARGE_TYPE_ARCHIVED,
                    "The charge type is archived and cannot get tariffs.");
        }
        return type;
    }

    /** Saves and flushes, so a second version on the same date surfaces here as {@code TARIFF_VERSION_DATE_TAKEN}. */
    private TariffVersionRecord saveVersion(TariffVersionRecord version) {
        try {
            return versions.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            throw dateTakenOrRethrow(e);
        }
    }

    /** Flushes a corrected version, so a date that another version has taken surfaces here. */
    private void flushVersion(TariffVersionRecord version) {
        try {
            versions.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            throw dateTakenOrRethrow(e);
        }
    }

    /** The 409 of a date that another version of the tariff has taken, or the original exception. */
    private static RuntimeException dateTakenOrRethrow(DataIntegrityViolationException e) {
        if (ConstraintViolations.violates(e, DATE_INDEX)) {
            return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TARIFF_VERSION_DATE_TAKEN,
                    "A version of this tariff starts on this date already.",
                    List.of(new FieldProblem("validFrom", ProblemCodes.TARIFF_VERSION_DATE_TAKEN)));
        }
        return e;
    }

    /**
     * The terms of the request, or null with the problems added to {@code problems}: a rate that does not fit
     * ({@code RATE_INVALID}), a frequency that does not go with the basis ({@code FREQUENCY_BASIS_MISMATCH}), a missing
     * due date of a one-time amount ({@code REQUIRED}) or a due date of any other basis ({@code INVALID}). Field names
     * get {@code prefix} (for example {@code firstVersion.}).
     */
    private static @Nullable VersionTerms terms(TariffVersionCreate input, String prefix, List<FieldProblem> problems) {
        Rate rate = null;
        try {
            rate = Rate.parse(input.getRate());
        } catch (InvalidRateException e) {
            problems.add(new FieldProblem(prefix + "rate", ProblemCodes.RATE_INVALID));
        }
        Basis basis = Basis.valueOf(input.getBasis().name());
        Frequency frequency = Frequency.valueOf(input.getFrequency().name());
        for (Violation violation : VersionTerms.violations(basis, frequency, input.getDueDate())) {
            problems.add(switch (violation) {
                case FREQUENCY_BASIS_MISMATCH -> new FieldProblem(prefix + "frequency",
                        ProblemCodes.FREQUENCY_BASIS_MISMATCH);
                case DUE_DATE_REQUIRED -> new FieldProblem(prefix + "dueDate", ProblemCodes.REQUIRED);
                case DUE_DATE_NOT_ALLOWED -> new FieldProblem(prefix + "dueDate", ProblemCodes.INVALID);
            });
        }
        if (rate == null || !problems.isEmpty()) {
            return null;
        }
        return new VersionTerms(input.getValidFrom(), basis, frequency, rate, input.getDueDate());
    }

    /** The fields of a version as new values; {@code setOnly} leaves out an unset due date and note (as on create). */
    private static List<FieldChange> versionFields(TariffVersionRecord version, boolean setOnly) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("validFrom", null, version.getValidFrom().toString()));
        changes.add(new FieldChange("basis", null, version.getBasis().name()));
        changes.add(new FieldChange("frequency", null, version.getFrequency().name()));
        changes.add(new FieldChange("rate", null, version.getRate().toString()));
        if (!setOnly || version.getDueDate() != null) {
            changes.add(new FieldChange("dueDate", null,
                    version.getDueDate() == null ? null : version.getDueDate().toString()));
        }
        if (!setOnly || version.getNote() != null) {
            changes.add(new FieldChange("note", null, version.getNote()));
        }
        return changes;
    }

    private void publish(AccessProfile actor, String action, TariffRecord tariff, List<FieldChange> changes) {
        audit.publish(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action,
                TariffAuditPublisher.TARIFF, tariff.getId(), tariff.getName(), changes);
    }

    /** Publishes the audit entry of an applied version change, if it changed anything. */
    private void publish(Applied applied) {
        if (applied.event() != null) {
            audit.publish(applied.event());
        }
    }

    /** The audit entry of a version change, not published. */
    private AuditEvent event(AccessProfile actor, String action, TariffRecord tariff, List<FieldChange> changes) {
        return audit.event(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action,
                TariffAuditPublisher.TARIFF, tariff.getId(), tariff.getName(), changes);
    }

    /** The trimmed name; a blank one (spaces only) is {@code REQUIRED}, the contract limits the length. */
    private static String cleanName(@Nullable String raw, List<FieldProblem> problems) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            problems.add(new FieldProblem("name", ProblemCodes.REQUIRED));
        }
        return name;
    }

    static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiProblemException invalid(List<FieldProblem> problems) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", problems);
    }
}
