package app.nadel.tariffs.internal;

import app.nadel.audit.Actor;
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
 * Creates tariffs and adds versions. Every method is one transaction that also publishes the audit event. The
 * cooperative always comes from the caller's {@link AccessProfile}, never from the request; the caller must hold
 * {@code TARIFFS_VIEW} and {@code TARIFFS_MANAGE} (checked on the controller).
 * <p>
 * The charge type is locked {@code FOR SHARE} first, so a concurrent archive of it waits for this transaction (or this
 * one sees it archived). Versions are append-only: nothing here updates or deletes one. The rate is never rounded;
 * {@link Rate#parse} refuses what does not fit, and the one-off rule is that of {@link VersionTerms}.
 */
@Service
class TariffCommands {

    private static final String DATE_INDEX = "tariff_version_valid_from_uq";

    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final ChargeTypeRepository chargeTypes;
    private final PlotCatalog plots;
    private final TariffAssembler assembler;
    private final TariffAuditPublisher audit;
    private final EntityManager entityManager;
    private final Clock clock;

    TariffCommands(TariffRepository tariffs, TariffVersionRepository versions, TariffScopePlotRepository scopePlots,
            ChargeTypeRepository chargeTypes, PlotCatalog plots, TariffAssembler assembler,
            TariffAuditPublisher audit, EntityManager entityManager, Clock clock) {
        this.tariffs = tariffs;
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.chargeTypes = chargeTypes;
        this.plots = plots;
        this.assembler = assembler;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
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
        changes.add(new FieldChange("chargeType", null, chargeType.getName()));
        changes.addAll(versionFields(version));
        publish(actor, "TARIFF_CREATED", tariff, chargeType, changes);
        return assembler.assemble(tariff, chargeType);
    }

    @Transactional
    TariffVersion addVersion(UUID tariffId, TariffVersionCreate input, AccessProfile actor) {
        List<FieldProblem> problems = new ArrayList<>();
        VersionTerms terms = terms(input, "", problems);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }

        TariffRecord tariff = tariffs.findById(tariffId).filter(t -> t.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
        ChargeTypeRecord chargeType = lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = saveVersion(TariffVersionRecord.create(Objects.requireNonNull(actor.tenantId()),
                tariff.getId(), Objects.requireNonNull(terms), blankToNull(input.getNote()), Instant.now()));

        publish(actor, "TARIFF_VERSION_ADDED", tariff, chargeType, versionFields(version));
        // the end of the new version depends on the versions that start after it
        List<TariffVersion> all = TariffDtoMapper.versionsNewestFirst(
                versions.findByTariffIdOrderByValidFromAsc(tariff.getId()), LocalDate.now(clock));
        return all.stream().filter(v -> v.getId().equals(version.getId())).findFirst().orElseThrow();
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

        publishScopeAudit(actor, tariff, chargeType, change, facts);
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
    private void publishScopeAudit(AccessProfile actor, TariffRecord tariff, ChargeTypeRecord chargeType,
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
            publish(actor, "TARIFF_SCOPE_CHANGED", tariff, chargeType, changes);
        }
        Map<UUID, String> removed = change.removedExclusions();
        Map<UUID, String> added = change.addedExclusions();
        for (UUID plot : order) {
            if (removed.containsKey(plot)) {
                publish(actor, "TARIFF_EXCLUSION_REMOVED", tariff, chargeType, List.of(
                        new FieldChange("plot", facts.get(plot).number(), null),
                        new FieldChange("reason", removed.get(plot), null)));
            }
        }
        for (UUID plot : order) {
            if (added.containsKey(plot)) {
                publish(actor, "TARIFF_EXCLUSION_ADDED", tariff, chargeType, List.of(
                        new FieldChange("plot", null, facts.get(plot).number()),
                        new FieldChange("reason", null, added.get(plot))));
            }
        }
    }

    /** The charge type of the caller's cooperative, locked {@code FOR SHARE}; 404 when missing, 409 when archived. */
    private ChargeTypeRecord lockChargeType(UUID id, AccessProfile actor) {
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
            if (ConstraintViolations.violates(e, DATE_INDEX)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TARIFF_VERSION_DATE_TAKEN,
                        "A version of this tariff starts on this date already.",
                        List.of(new FieldProblem("validFrom", ProblemCodes.TARIFF_VERSION_DATE_TAKEN)));
            }
            throw e;
        }
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

    private static List<FieldChange> versionFields(TariffVersionRecord version) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("validFrom", null, version.getValidFrom().toString()));
        changes.add(new FieldChange("basis", null, version.getBasis().name()));
        changes.add(new FieldChange("frequency", null, version.getFrequency().name()));
        changes.add(new FieldChange("rate", null, version.getRate().toString()));
        if (version.getDueDate() != null) {
            changes.add(new FieldChange("dueDate", null, version.getDueDate().toString()));
        }
        if (version.getNote() != null) {
            changes.add(new FieldChange("note", null, version.getNote()));
        }
        return changes;
    }

    private void publish(AccessProfile actor, String action, TariffRecord tariff, ChargeTypeRecord chargeType,
            List<FieldChange> changes) {
        audit.publish(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action,
                TariffAuditPublisher.TARIFF, tariff.getId(), chargeType.getName() + " / " + tariff.getName(), changes);
    }

    /** The trimmed name; a blank one (spaces only) is {@code REQUIRED}, the contract limits the length. */
    private static String cleanName(@Nullable String raw, List<FieldProblem> problems) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            problems.add(new FieldProblem("name", ProblemCodes.REQUIRED));
        }
        return name;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
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
